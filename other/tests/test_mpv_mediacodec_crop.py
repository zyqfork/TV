"""Compile and exercise the exact hardware-frame crop transfer, not a Python rewrite.
Host test/static wiring only: real Android pixels are a separate acceptance step.
"""
import ast
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

source = Path('media3compat/scripts/apply_subtitle_overlay.py').read_text(encoding='utf8')
replacements = [n.args[2].value for n in ast.walk(ast.parse(source))
                if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
                and n.func.id == 'patch' and len(n.args) >= 3
                and isinstance(n.args[2], ast.Constant) and isinstance(n.args[2].value, str)]
replacement = next(s for s in replacements if 'FONGMI_MEDIACODEC_FRAME_CROP' in s)
body = 'frame->width = avctx->width;' + replacement.split('frame->width = avctx->width;', 1)[1].split('frame->format = avctx->pix_fmt;', 1)[0]
probe = r'''
#include <assert.h>
#include <stdint.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
typedef struct {int width, height;} Ctx;
typedef struct {int crop_left, crop_top, crop_right, crop_bottom;} Crop;
typedef struct {int width, height; size_t crop_left, crop_top;} Frame;
static void transfer(Ctx *avctx, Crop *s, Frame *frame) { BODY }
static void check(int w,int h,int l,int t,int r,int b,int fw,int fh,int cl,int ct) {
 Ctx ctx={w,h}; Crop s={l,t,r,b}; Frame f={0};
 transfer(&ctx,&s,&f);
 assert(f.width==fw && f.height==fh && f.crop_left==(size_t)cl && f.crop_top==(size_t)ct);
 assert(ctx.width==w && ctx.height==h);
 // Same context across frames: origins must never accumulate in decoder dimensions.
 Frame f2={0}; transfer(&ctx,&s,&f2);
 assert(f2.width==fw && f2.height==fh);
}
int main(void) {
 check(3840,2160,0,0,3839,2159,3840,2160,0,0);
 check(3840,2160,0,4,3839,2163,3840,2164,0,4);
 check(1920,1080,32,16,1951,1095,1952,1096,32,16);
 check(1280,720,0,0,0,0,1280,720,0,0); // missing crop
 check(1280,720,-1,4,1278,723,1280,720,0,0); // negative origin
 check(1280,720,0,4,1279,719,1280,720,0,0); // inconsistent endpoints
 check(2,1,INT_MAX-1,0,INT_MAX,0,2,1,0,0); // overflow
 check(1,1,0,0,0,0,1,1,0,0);
 puts("PASS exact C hardware crop: inclusive endpoints, all visible pixels, zero/missing/malformed origins, overflow and no per-frame growth");
}
'''.replace('BODY', body)
cc = os.environ.get('CC') or shutil.which('cc') or shutil.which('gcc')
if not cc:
    raise RuntimeError('Set CC to a host C compiler; no mock pass is permitted')
with tempfile.TemporaryDirectory() as tmp:
    c = Path(tmp)/'crop.c'; exe=Path(tmp)/('crop.exe' if os.name=='nt' else 'crop')
    c.write_text(probe, encoding='utf8')
    subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror', str(c), '-o', str(exe)], check=True)
    subprocess.run([str(exe)], check=True)
assert 'mapper->tex[0]->params.w = (int)d.width;' in source
assert 'mapper->tex[0]->params.h = (int)d.height;' in source
assert 'frame->width += s->crop_left;' in replacement
assert 'frame->height += s->crop_top;' in replacement
assert 'c2.rk' not in replacement and 'crop_top = 4' not in replacement
print('PASS STATIC: declared crop origin and actual storage dimensions; no vendor gate or fixed border deletion. Does not prove Android pixels.')
