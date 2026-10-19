#!/usr/bin/env python3
"""Render original Daybook vector branding. Optional build dependency: Pillow 12.3."""
import argparse
from pathlib import Path
import math
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
PURPLE = "#7165D8"
def q(start, control, end, steps=24):
    return [((1-t)**2*start[0]+2*(1-t)*t*control[0]+t*t*end[0], (1-t)**2*start[1]+2*(1-t)*t*control[1]+t*t*end[1]) for t in [i/steps for i in range(1,steps+1)]]
def dmark(draw, box, color="white", background=PURPLE):
    x,y,w,h=box
    def points(raw): return [(x+a*w/48,y+b*h/48) for a,b in raw]
    outer=[(13,11),(28,11)]+q((28,11),(36,11),(36,24))+q((36,24),(36,37),(28,37))+[(13,37)]
    inner=[(19,17),(19,31),(27,31)]+q((27,31),(30,31),(30,24))+q((30,24),(30,17),(27,17))
    draw.polygon(points(outer),fill=color); draw.polygon(points(inner),fill=background)
def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--font",required=True); args=parser.parse_args()
    folder=ROOT/"publishing/assets"; folder.mkdir(parents=True,exist_ok=True)
    image=Image.new("RGB",(1536,1536),PURPLE); dmark(ImageDraw.Draw(image),(0,0,1536,1536))
    image.resize((512,512),Image.Resampling.LANCZOS).save(folder/"icon-512.png")
    image=Image.new("RGB",(1024,500)); draw=ImageDraw.Draw(image)
    for y in range(500):
        t=y/499; color=tuple(round(a*(1-t)+b*t) for a,b in zip((239,247,255),(248,240,255)))
        draw.line((0,y,1024,y),fill=color)
    font=lambda size: ImageFont.truetype(args.font,size)
    draw.rounded_rectangle((52,54,126,128),radius=19,fill=PURPLE); dmark(draw,(52,54,74,74))
    draw.text((52,161),"Daybook",font=font(66),fill="#292435")
    draw.text((55,250),"Your day, in your words.",font=font(29),fill="#514960")
    draw.text((55,310),"Voice  ·  Photos  ·  Goals",font=font(22),fill="#6D607C")
    draw.text((55,401),"Capture. Remember. Reflect.",font=font(19),fill="#7165D8")
    draw.rounded_rectangle((676,65,964,435),radius=38,fill="#211E2D")
    draw.ellipse((771,111,869,209),fill="#BFB1FF")
    # Microphone geometry is an original code-native icon; no downloaded assets.
    draw.rounded_rectangle((809,129,831,164),radius=11,fill="#241B39")
    draw.arc((799,142,841,181),0,180,fill="#241B39",width=5)
    draw.line((820,181,820,191),fill="#241B39",width=5); draw.line((808,191,832,191),fill="#241B39",width=5)
    draw.text((739,233),"A thought, kept.",font=font(20),fill="#F3EEFF")
    for i in range(21):
        height=12+int(40*abs(math.sin(i*1.3)))
        x=711+i*10
        draw.rounded_rectangle((x,317-height//2,x+4,317+height//2),radius=2,fill="#BFB1FF")
    draw.text((745,371),"Made for every day.",font=font(16),fill="#C6BBD7")
    image.save(folder/"feature-1024x500.png")
    print("Rendered original Daybook store assets")
if __name__=="__main__": main()
