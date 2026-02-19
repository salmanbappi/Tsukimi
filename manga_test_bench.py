from PIL import Image, ImageDraw
import numpy as np
import os
import sys

class MangaTestBench:
    def __init__(self):
        self.luminance_threshold = 210  # Stricter border detection
        self.scan_tolerance = 2
        
    def is_pixel_light(self, r, g, b):
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum >= self.luminance_threshold

    def enhance_for_ocr(self, img_np):
        alpha = 1.8
        beta = -60
        enhanced = np.clip(img_np.astype(np.float32) * alpha + beta, 0, 255).astype(np.uint8)
        return enhanced

    def scan(self, img_np, start_x, start_y, dx, dy, max_dist):
        h, w = img_np.shape[:2]
        x, y = start_x, start_y
        dist = 0
        tolerance = self.scan_tolerance
        last_valid_dist = 0
        
        while dist < max_dist:
            x += dx
            y += dy
            if x < 0 or x >= w or y < 0 or y >= h:
                break
            
            r, g, b = img_np[y, x][:3]
            if self.is_pixel_light(r, g, b):
                dist += 1
                last_valid_dist = dist
                tolerance = self.scan_tolerance 
            else:
                if tolerance > 0:
                    dist += 1
                    tolerance -= 1
                else:
                    break
        return last_valid_dist

    def detect_bubble_bounds(self, img_np, text_rect):
        h, w = img_np.shape[:2]
        tx, ty, tw, th = text_rect 
        
        # Dense scanning (5 points) for better round bubble fitting
        points_x = [tx + int(tw * p) for p in [0, 0.25, 0.5, 0.75, 1.0]]
        points_y = [ty + int(th * p) for p in [0, 0.25, 0.5, 0.75, 1.0]]
        
        max_expand_x = int(min(tw * 0.6, w / 5))
        max_expand_y = int(min(th * 0.5, h / 8))

        left_dist = max([self.scan(img_np, tx, py, -1, 0, max_expand_x) for py in points_y])
        right_dist = max([self.scan(img_np, tx + tw, py, 1, 0, max_expand_x) for py in points_y])
        top_dist = max([self.scan(img_np, px, ty, 0, -1, max_expand_y) for px in points_x])
        bottom_dist = max([self.scan(img_np, px, ty + th, 0, 1, max_expand_y) for px in points_x])

        return [tx - left_dist, ty - top_dist, tw + left_dist + right_dist, th + top_dist + bottom_dist]

    def resolve_overlapping_blocks(self, boxes):
        """Mirroring the Kotlin resolveOverlappingBlocks logic."""
        if not boxes: return []
        boxes.sort(key=lambda b: b[2] * b[3], reverse=True)
        resolved = []
        for box in boxes:
            merged = False
            bx, by, bw, bh = box
            for i in range(len(resolved)):
                ex, ey, ew, eh = resolved[i]
                
                # Check intersection
                ix = max(bx, ex)
                iy = max(by, ey)
                iw = min(bx+bw, ex+ew) - ix
                ih = min(by+bh, ey+eh) - iy
                
                if iw > 0 and ih > 0:
                    overlap_area = iw * ih
                    box_area = bw * bh
                    if overlap_area > box_area * 0.4:
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

    def find_text_candidates(self, img):
        from scipy.ndimage import label, find_objects
        gray = img.convert("L")
        mask = np.array(gray) < 60
        labeled, num = label(mask)
        objs = find_objects(labeled)
        boxes = []
        for obj in objs:
            y_slice, x_slice = obj
            h = y_slice.stop - y_slice.start
            w = x_slice.stop - x_slice.start
            if 15 < h < 500 and 8 < w < 100 and h > w * 1.2:
                boxes.append([x_slice.start, y_slice.start, w, h])
        return boxes

    def run_test(self, image_path, output_path):
        try:
            img = Image.open(image_path).convert("RGB")
        except Exception as e:
            print(f"Error loading {image_path}: {e}")
            return
        
        img_np = np.array(img)
        enhanced_np = self.enhance_for_ocr(img_np)
        
        ocr_boxes = self.find_text_candidates(img)
        
        # 1. Expand each box into a bubble
        bubble_candidates = [self.detect_bubble_bounds(enhanced_np, box) for box in ocr_boxes]
        
        # 2. RESOLVE OVERLAPS (This is the magic part)
        final_bubbles = self.resolve_overlapping_blocks(bubble_candidates)
        
        draw = ImageDraw.Draw(img)
        for bubble in final_bubbles:
            bx, by, bw, bh = bubble
            # Final result: Thick blue boxes for resolved bubbles
            draw.rectangle([bx, by, bx+bw, by+bh], outline="cyan", width=5)
            
        img.save(output_path)
        print(f"Processed {image_path} -> {output_path} (Resolved into {len(final_bubbles)} clean bubbles)")

if __name__ == "__main__":
    import sys
    bench = MangaTestBench()
    bench.run_test(sys.argv[1], sys.argv[2])