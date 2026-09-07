from pathlib import Path
from pypdf import PdfReader
from pdf2image import convert_from_path
from PIL import Image, ImageOps, ImageDraw
import sys
p=Path(sys.argv[1] if len(sys.argv)>1 else 'D:/Temp/ClawAssistant/知行作品报告书.pdf')
o=Path(sys.argv[2] if len(sys.argv)>2 else 'D:/Temp/ClawAssistant/tmp/zhixing-word-pages');o.mkdir(exist_ok=True)
r=PdfReader(p)
for i,pg in enumerate(r.pages):
    t=pg.extract_text();print(i+1,len(t),t[:80].replace('\n',' '))
imgs=convert_from_path(str(p),dpi=120)
for i,im in enumerate(imgs):im.save(o/f'page-{i+1}.png')
thumbs=[]
for i,im in enumerate(imgs):
    im.thumbnail((420,595)); tile=Image.new('RGB',(440,625),'#dddddd');tile.paste(im,(10,25));ImageDraw.Draw(tile).text((10,5),str(i+1),fill='black');thumbs.append(tile)
sheet=Image.new('RGB',(440*3,625*((len(imgs)+2)//3)),'white')
for i,im in enumerate(thumbs):sheet.paste(im,((i%3)*440,(i//3)*625))
sheet.save(o/'contact.png')
