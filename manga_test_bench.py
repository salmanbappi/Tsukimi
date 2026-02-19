from PIL import Image, ImageDraw
import numpy as np
import os
import sys

class MangaTestBench:
    def __init__(self):
        self.scan_tolerance = 3
        
    def get_luminance(self, r, g, b):
        return 0.299 * r + 0.587 * g + 0.114 * b

    def find_text_candidates(self, img):
        from scipy.ndimage import label, find_objects, binary_dilation
        gray = img.convert("L")
        # Find dark strokes
        mask = np.array(gray) < 110
        # Group strokes into blocks
        mask = binary_dilation(mask, iterations=18)
        
        labeled, num = label(mask)
        objs = find_objects(labeled)
        boxes = []
        for obj in objs:
            y_slice, x_slice = obj
            h = y_slice.stop - y_slice.start
            w = x_slice.stop - x_slice.start
            if h > 15 and w > 10:
                boxes.append([x_slice.start, y_slice.start, w, h])
        return boxes

    def are_blocks_close(self, b1, b2):
        """Python implementation of the refined Kotlin areBlocksClose."""
        x1, y1, w1, h1 = b1
        x2, y2, w2, h2 = b2
        
        # Vertical column check (Manga style)
        is_vertical = h1 > w1 * 1.2
        
        if is_vertical:
            # Overlap on shared axis?
            if min(y1+h1, y2+h2) - max(y1, y2) < 0: return False
            avg_h = (h1 + h2) / 2.0
            threshold_x = max(10, min(45, avg_h * 0.45))
            threshold_y = max(2, min(12, avg_h * 0.15))
        else:
            if min(x1+w1, x2+w2) - max(x1, x2) < 0: return False
            avg_w = (w1 + w2) / 2.0
            threshold_x = max(2, min(12, avg_w * 0.15))
            threshold_y = max(10, min(45, avg_w * 0.45))
            
        # Check intersection with expansion
        ex1, ey1 = x1 - threshold_x, y1 - threshold_y
        ew1, eh1 = w1 + 2*threshold_x, h1 + 2*threshold_y
        
        ix = max(ex1, x2)
        iy = max(ey1, y2)
        iw = min(ex1+ew1, x2+w2) - ix
        ih = min(ey1+eh1, y2+h2) - iy
        
        return iw > 0 and ih > 0

    def resolve_overlapping_blocks(self, boxes):
        if not boxes: return []
        boxes.sort(key=lambda b: b[2] * b[3], reverse=True)
        resolved = []
        for box in boxes:
            merged = False
            for i in range(len(resolved)):
                if self.are_blocks_close(resolved[i], box):
                    ex, ey, ew, eh = resolved[i]
                    bx, by, bw, bh = box
                    nx = min(bx, ex)
                    ny = min(by, ey)
                    nw = max(bx+bw, ex+ew) - nx
                    nh = max(by+bh, ey+eh) - ny
                    resolved[i] = [nx, ny, nw, nh]
                    merged = True
                    break
            if not merged:
                resolved.append(box)
        return resolved

    def scan(self, img_np, start_x, start_y, dx, dy, max_dist, threshold):
        h, w = img_np.shape[:2]
        x, y = start_x, start_y
        dist = 0
        tolerance = self.scan_tolerance
        last_valid_dist = 0
        while dist < max_dist:
            x += dx
            y += dy
            if x < 0 or x >= w or y < 0 or y >= h: break
            r, g, b = img_np[y, x][:3]
            if self.get_luminance(r, g, b) >= threshold:
                dist += 1
                last_valid_dist = dist
                tolerance = self.scan_tolerance
            else:
                if tolerance > 0:
                    dist += 1
                    tolerance -= 1
                else: break
        return last_valid_dist

    def detect_bubble_bounds(self, img_np, text_rect):
        h, w = img_np.shape[:2]
        tx, ty, tw, th = text_rect 
        
        # ADAPTIVE: Sample center luminance
        cx, cy = tx + tw//2, ty + th//2
        if 0 <= cx < w and 0 <= cy < h:
            cp = img_np[cy, cx]
            center_lum = self.get_luminance(cp[0], cp[1], cp[2])
            local_threshold = max(180.0, min(215.0, center_lum * 0.92))
        else:
            local_threshold = 210.0

        max_expand_x = max(40, min(w//4, int(tw * 0.7)))
        max_expand_y = max(40, min(h//6, int(th * 0.6)))

        # 5-point scan
        pxs = [tx + int(tw * p) for p in [0, 0.25, 0.5, 0.75, 1.0]]
        pys = [ty + int(th * p) for p in [0, 0.25, 0.5, 0.75, 1.0]]
        
        ld = max([self.scan(img_np, tx, py, -1, 0, max_expand_x, local_threshold) for py in pys])
        rd = max([self.scan(img_np, tx+tw, py, 1, 0, max_expand_x, local_threshold) for py in pys])
        td = max([self.scan(img_np, px, ty, 0, -1, max_expand_y, local_threshold) for px in pxs])
        bd = max([self.scan(img_np, px, ty+th, 0, 1, max_expand_y, local_threshold) for px in pxs])

        safety = 8
        return [tx-ld-safety, ty-td-safety, tw+ld+rd+2*safety, th+td+bd+2*safety]

    def run_test(self, image_path, output_path):
        img = Image.open(image_path).convert("RGB")
        img_np = np.array(img)
        
        candidates = self.find_text_candidates(img)
        bubbles = [self.detect_bubble_bounds(img_np, c) for c in candidates]
        final_bubbles = self.resolve_overlapping_blocks(bubbles)
        
        draw = ImageDraw.Draw(img)
        for b in final_bubbles:
            bx, by, bw, bh = b
            c1 = img.getpixel((max(0, bx), max(0, by)))
            draw.rectangle([bx, by, bx+bw, by+bh], fill=c1)
            lum = self.get_luminance(*c1)
            draw.text((bx+5, by+5), "TEST", fill="black" if lum > 160 else "white")
            
        img.save(output_path)
        print(f"Processed {image_path} -> {output_path} ({len(final_bubbles)} blocks)")

if __name__ == "__main__":
    bench = MangaTestBench()
    bench.run_test(sys.argv[1], sys.argv[2])