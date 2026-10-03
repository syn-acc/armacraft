"""Light cycle pilot skin (64x64, classic 4px arms).

Writes three textures and a preview into art/pilot/:
  pilot_base.png   the suit and helmet, no light lines
  pilot_glow.png   the light lines only: white, alpha 255 = primary line, 140 = secondary (tint it per rider)
  pilot_<name>.png base + glow baked in one colour, usable as a normal Minecraft skin
  pilot_<name>_preview.png  front / side / back views

  python tools/pilot_skin.py [--rgb BB0022] [--name syn]
"""
import argparse
import os

from PIL import Image

# face rectangles (x, y, w, h) per part: top, bottom, right, front, left, back
def box(x, y, w, h, d):
	return {
		"top": (x + d, y, w, d), "bottom": (x + d + w, y, w, d),
		"right": (x, y + d, d, h), "front": (x + d, y + d, w, h),
		"left": (x + d + w, y + d, d, h), "back": (x + d + w + d, y + d, w, h),
	}

HEAD = box(0, 0, 8, 8, 8)
BODY = box(16, 16, 8, 12, 4)
R_ARM = box(40, 16, 4, 12, 4)
L_ARM = box(32, 48, 4, 12, 4)
R_LEG = box(0, 16, 4, 12, 4)
L_LEG = box(16, 48, 4, 12, 4)

SUIT = (13, 15, 20)
PLATE = (24, 27, 35)
HELMET = (17, 19, 26)
VISOR = (4, 6, 10)
VISOR_SHINE = (46, 58, 74)
PRIMARY, SECONDARY = 255, 140


class Skin:
	def __init__(self):
		self.base = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
		self.glow = Image.new("RGBA", (64, 64), (0, 0, 0, 0))

	def fill(self, part, col):
		for name, (x, y, w, h) in part.items():
			for i in range(w):
				for j in range(h):
					# a little per-pixel variation so the suit is not a flat fill
					n = ((x + i) * 7 + (y + j) * 13) % 5 - 2
					self.base.putpixel((x + i, y + j), tuple(max(0, c + n) for c in col) + (255,))

	def paint(self, part, face, px, py, col):
		x, y, w, h = part[face]
		if 0 <= px < w and 0 <= py < h:
			self.base.putpixel((x + px, y + py), col + (255,))

	def light(self, part, face, px, py, level=PRIMARY):
		x, y, w, h = part[face]
		if 0 <= px < w and 0 <= py < h:
			self.glow.putpixel((x + px, y + py), (255, 255, 255, level))


def head(s):
	s.fill(HEAD, HELMET)
	# visor: a band across the front, wrapping 3 px onto each side
	for py in range(3, 6):
		for px in range(1, 7):
			s.paint(HEAD, "front", px, py, VISOR)
		for px in range(5, 8):
			s.paint(HEAD, "right", px, py, VISOR)
		for px in range(0, 3):
			s.paint(HEAD, "left", px, py, VISOR)
	s.paint(HEAD, "front", 1, 3, VISOR_SHINE)
	s.paint(HEAD, "front", 2, 4, VISOR_SHINE)
	# crest: over the crown and down the back to the neck
	for px in (3, 4):
		for py in range(0, 2):
			s.light(HEAD, "front", px, py)
		for py in range(0, 8):
			s.light(HEAD, "top", px, py)
			s.light(HEAD, "back", px, py, PRIMARY if py < 5 else SECONDARY)
	# chin line and ear pieces
	for px in range(2, 6):
		s.light(HEAD, "front", px, 7, SECONDARY)
	for py in (4, 5):
		for px in (2, 3):
			s.light(HEAD, "right", px, py)
		for px in (4, 5):
			s.light(HEAD, "left", px, py)


def body(s):
	s.fill(BODY, SUIT)
	for px in range(1, 7):  # chest plate
		for py in range(1, 4):
			s.paint(BODY, "front", px, py, PLATE)
	# shoulder lines converge, then run down to the belt
	for i in range(3):
		s.light(BODY, "front", i, i + 1)
		s.light(BODY, "front", 7 - i, i + 1)
	for py in range(4, 10):
		s.light(BODY, "front", 2, py, SECONDARY)
		s.light(BODY, "front", 5, py, SECONDARY)
	for px in (3, 4):  # core
		for py in (5, 6):
			s.light(BODY, "front", px, py)
	for face in ("front", "back", "left", "right"):  # belt
		for px in range(8):
			s.light(BODY, face, px, 11, SECONDARY)
	# back: a ring between the shoulders, a spine line below it
	ring = [(2, 2), (3, 2), (4, 2), (5, 2), (1, 3), (6, 3), (1, 4), (6, 4), (1, 5), (6, 5), (1, 6), (6, 6), (2, 7), (3, 7), (4, 7), (5, 7)]
	for px, py in ring:
		s.light(BODY, "back", px, py)
	for px in (3, 4):
		for py in (4, 5):
			s.light(BODY, "back", px, py, SECONDARY)
		for py in range(8, 11):
			s.light(BODY, "back", px, py, SECONDARY)


def arm(s, part, outer):
	s.fill(part, SUIT)
	col = 1 if outer == "right" else 2
	for py in range(1, 9):
		s.light(part, outer, col, py)
	for px in range(1, part["top"][2] - 1):  # shoulder cap
		for py in (1, 2):
			s.light(part, "top", px, py)
	for face in ("front", "back", "left", "right"):
		for px in range(4):
			s.light(part, face, px, 9, SECONDARY)  # cuff
			for py in (10, 11):
				s.paint(part, face, px, py, PLATE)  # glove


def leg(s, part, outer):
	s.fill(part, SUIT)
	col = 1 if outer == "right" else 2
	for py in range(0, 8):
		s.light(part, outer, col, py)
	for px in range(4):  # knee
		s.paint(part, "front", px, 5, PLATE)
		s.paint(part, "front", px, 6, PLATE)
	for face in ("front", "back", "left", "right"):
		for px in range(4):
			for py in (9, 10):
				s.paint(part, face, px, py, PLATE)  # boot
			s.light(part, face, px, 11)  # sole
			s.light(part, face, px, 8, SECONDARY)


def bake(s, rgb):
	out = s.base.copy()
	for x in range(64):
		for y in range(64):
			a = s.glow.getpixel((x, y))[3]
			if a:
				k = 1.0 if a == PRIMARY else 0.5
				out.putpixel((x, y), tuple(int(c * k) for c in rgb) + (255,))
	return out


def saturate(rgb):
	"""Scale the colour so its largest channel is 255: a light, not a paint."""
	m = max(rgb) or 1
	return tuple(min(255, round(c * 255 / m)) for c in rgb)


def preview(skin, scale=14):
	def face(part, name):
		x, y, w, h = part[name]
		return skin.crop((x, y, x + w, y + h))
	views = []
	front = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
	for part, name, at in ((HEAD, "front", (4, 0)), (BODY, "front", (4, 8)), (R_ARM, "front", (0, 8)), (L_ARM, "front", (12, 8)),
			(R_LEG, "front", (4, 20)), (L_LEG, "front", (8, 20))):
		front.paste(face(part, name), at)
	side = Image.new("RGBA", (8, 32), (0, 0, 0, 0))
	for part, name, at in ((HEAD, "right", (0, 0)), (R_LEG, "right", (2, 20)), (R_ARM, "right", (2, 8))):
		side.paste(face(part, name), at)
	back = Image.new("RGBA", (16, 32), (0, 0, 0, 0))
	for part, name, at in ((HEAD, "back", (4, 0)), (BODY, "back", (4, 8)), (L_ARM, "back", (0, 8)), (R_ARM, "back", (12, 8)),
			(L_LEG, "back", (4, 20)), (R_LEG, "back", (8, 20))):
		back.paste(face(part, name), at)
	views = [front, side, back]
	gap = 6
	w = sum(v.width for v in views) + gap * (len(views) + 1)
	sheet = Image.new("RGBA", (w, 32 + gap * 2), (58, 66, 80, 255))
	x = gap
	for v in views:
		sheet.paste(v, (x, gap), v)
		x += v.width + gap
	return sheet.resize((sheet.width * scale, sheet.height * scale), Image.NEAREST)


def main():
	ap = argparse.ArgumentParser()
	ap.add_argument("--rgb", default="BB0022")
	ap.add_argument("--name", default="syn")
	ap.add_argument("--slim", action="store_true", help="3px arms (the slim player model)")
	ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "art", "pilot"))
	a = ap.parse_args()
	rgb = saturate(tuple(int(a.rgb[i:i + 2], 16) for i in (0, 2, 4)))
	if a.slim:
		global R_ARM, L_ARM
		R_ARM, L_ARM = box(40, 16, 3, 12, 4), box(32, 48, 3, 12, 4)
	s = Skin()
	head(s)
	body(s)
	arm(s, R_ARM, "right")
	arm(s, L_ARM, "left")
	leg(s, R_LEG, "right")
	leg(s, L_LEG, "left")
	os.makedirs(a.out, exist_ok=True)
	s.base.save(os.path.join(a.out, "pilot_base.png"))
	s.glow.save(os.path.join(a.out, "pilot_glow.png"))
	baked = bake(s, rgb)
	baked.save(os.path.join(a.out, f"pilot_{a.name}.png"))
	preview(baked).save(os.path.join(a.out, f"pilot_{a.name}_preview.png"))
	print("wrote", os.path.abspath(a.out), "rgb %02X%02X%02X" % rgb)


if __name__ == "__main__":
	main()
