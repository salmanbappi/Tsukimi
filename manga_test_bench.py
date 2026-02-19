from PIL import Image, ImageDraw
import numpy as np
import os
import sys

class MangaTestBench:
    def __init__(self):
        self.luminance_threshold = 210
        self.scan_tolerance = 2
        
    def is_pixel_light(self, r, g, b):
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        return lum >= self.luminance_threshold

    def enhance_for_ocr(self, img_np):
        alpha = 1.8
        beta = -60
        enhanced = np.clip(img_np.astype(np.float32) * alpha + beta, 0, 255).astype(np.uint8)
        return enhanced

    def find_text_candidates(self, img):
        from scipy.ndimage import label, find_objects, binary_dilation
        gray = img.convert("L")
        # Find dark strokes (Japanese text is usually dark)
        mask = np.array(gray) < 110
        # Aggressively dilate to group nearby strokes/characters into solid blocks
        # This is key for completely "erasing" the text area
        mask = binary_dilation(mask, iterations=18)
        
        labeled, num = label(mask)
        objs = find_objects(labeled)
        boxes = []
        for obj in objs:
            y_slice, x_slice = obj
            h = y_slice.stop - y_slice.start
            w = x_slice.stop - x_slice.start
            if h > 15 and w > 10:
                # Add a safe margin to ensure no stroke edges are visible
                boxes.append([x_slice.start - 8, y_slice.start - 8, w + 16, h + 16])
        return boxes

    def resolve_overlapping_blocks(self, boxes):
        if not boxes: return []
        boxes.sort(key=lambda b: b[2] * b[3], reverse=True)
        resolved = []
        for box in boxes:
            merged = False
            bx, by, bw, bh = box
            for i in range(len(resolved)):
                ex, ey, ew, eh = resolved[i]
                ix = max(bx, ex)
                iy = max(by, ey)
                iw = min(bx+bw, ex+ew) - ix
                ih = min(by+bh, ey+eh) - iy
                if iw > 0 and ih > 0:
                    overlap_area = iw * ih
                    box_area = bw * bh
                    if overlap_area > box_area * 0.3:
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

    def run_test(self, image_path, output_path):
        try:
            img = Image.open(image_path).convert("RGB")
        except Exception as e:
            print(f"Error loading {image_path}: {e}")
            return
        
        # 1. Detect text regions via morphological grouping
        ocr_boxes = self.find_text_candidates(img)
        
        # 2. Resolve overlapping regions into final masks
        final_bubbles = self.resolve_overlapping_blocks(ocr_boxes)
        
        draw = ImageDraw.Draw(img)
        for bubble in final_bubbles:
            bx, by, bw, bh = bubble
            
            # Sample background color from edge (to avoid the text itself)
            # Sampling 4 pixels from the corners of the bounding box
            try:
                c1 = img.getpixel((max(0, bx), max(0, by)))
                c2 = img.getpixel((min(img.width-1, bx+bw-1), max(0, by)))
                c3 = img.getpixel((max(0, bx), min(img.height-1, by+bh-1)))
                c4 = img.getpixel((min(img.width-1, bx+bw-1), min(img.height-1, by+bh-1)))
                
                avg_r = (c1[0] + c2[0] + c3[0] + c4[0]) // 4
                avg_g = (c1[1] + c2[1] + c3[1] + c4[1]) // 4
                avg_b = (c1[2] + c2[2] + c3[2] + c4[2]) // 4
                bg_color = (avg_r, avg_g, avg_b)
            except:
                bg_color = (255, 255, 255)
            
            # 3. DRAW SOLID MASK (The "Erased" look - 100% Opaque)
            draw.rectangle([bx, by, bx+bw, by+bh], fill=bg_color)
            
            # 4. DRAW PLACEHOLDER TEXT
            lum = 0.299 * bg_color[0] + 0.587 * bg_color[1] + 0.114 * bg_color[2]
            text_color = "black" if lum > 160 else "white"
            draw.text((bx + 5, by + 5), "TRANSLATED", fill=text_color)
            
        img.save(output_path)
        print(f"Processed {image_path} -> {output_path} (Resolved into {len(final_bubbles)} clean masks)")

if __name__ == "__main__":
    import sys
    bench = MangaTestBench()
    bench.run_test(sys.argv[1], sys.argv[2])
