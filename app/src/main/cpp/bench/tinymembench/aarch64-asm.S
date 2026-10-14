/*
 * 来源与版本声明（本注释块之外的内容与上游逐字节一致，未作任何修改）
 *   上游项目：tinymembench v0.4.9
 *   上游路径：upstream/tinymembench/aarch64-asm.S
 *   上游提交：a2cf6d7e382e3aea1eb39173174d9fa28cad15f3 (2017-02-14)
 *   上游 MD5：9b2be2cd29b8a4c405491935c0562917
 *   版权    ：Copyright © 2016 Siarhei Siamashka <siarhei.siamashka@gmail.com>
 *              MIT 许可；完整许可证原文见下方上游文件头。
 *   用途    ：AndroidBenech tinymembench 内核在 __aarch64__ 下的 NEON 拷贝/填充例程。
 *   注意    ：文件顶层自带 #ifdef __aarch64__，x86_64 上预处理为空文件，
 *              构建系统可以无条件将其加入源文件列表。
 */
/*
 * Copyright © 2016 Siarhei Siamashka <siarhei.siamashka@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice (including the next
 * paragraph) shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

#ifdef __aarch64__

    .cpu cortex-a53+fp+simd
    .text
    .align 2

#define PREFETCH_DISTANCE 320

.macro asm_function function_name
    .global \function_name
    .type \function_name,%function 
.func \function_name
\function_name:
    DST         .req x0
    SRC         .req x1
    SIZE        .req x2
.endm

asm_function aligned_block_copy_ldpstp_x_aarch64
0:
    ldp         x3,  x4, [SRC, #(0 * 16)]
    ldp         x5,  x6, [SRC, #(1 * 16)]
    ldp         x7,  x8, [SRC, #(2 * 16)]
    ldp         x9, x10, [SRC, #(3 * 16)]
    add         SRC, SRC, #64
    stp         x3,  x4, [DST, #(0 * 16)]
    stp         x5,  x6, [DST, #(1 * 16)]
    stp         x7,  x8, [DST, #(2 * 16)]
    stp         x9, x10, [DST, #(3 * 16)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_copy_ldpstp_q_aarch64
0:
    ldp         q0,  q1, [SRC, #(0 * 32)]
    ldp         q2,  q3, [SRC, #(1 * 32)]
    add         SRC, SRC, #64
    stp         q0,  q1, [DST, #(0 * 32)]
    stp         q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_copy_ldpstp_q_pf32_l2strm_aarch64
0:
    prfm        pldl2strm, [SRC, #(PREFETCH_DISTANCE +  0)]
    ldp         q0,  q1, [SRC, #(0 * 32)]
    prfm        pldl2strm, [SRC, #(PREFETCH_DISTANCE + 32)]
    ldp         q2,  q3, [SRC, #(1 * 32)]
    add         SRC, SRC, #64
    stp         q0,  q1, [DST, #(0 * 32)]
    stp         q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_copy_ldpstp_q_pf64_l2strm_aarch64
0:
    prfm        pldl2strm, [SRC, #(PREFETCH_DISTANCE)]
    ldp         q0,  q1, [SRC, #(0 * 32)]
    ldp         q2,  q3, [SRC, #(1 * 32)]
    add         SRC, SRC, #64
    stp         q0,  q1, [DST, #(0 * 32)]
    stp         q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_copy_ldpstp_q_pf32_l1keep_aarch64
0:
    prfm        pldl1keep, [SRC, #(PREFETCH_DISTANCE +  0)]
    ldp         q0,  q1, [SRC, #(0 * 32)]
    prfm        pldl1keep, [SRC, #(PREFETCH_DISTANCE + 32)]
    ldp         q2,  q3, [SRC, #(1 * 32)]
    add         SRC, SRC, #64
    stp         q0,  q1, [DST, #(0 * 32)]
    stp         q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_copy_ldpstp_q_pf64_l1keep_aarch64
0:
    prfm        pldl1keep, [SRC, #(PREFETCH_DISTANCE)]
    ldp         q0,  q1, [SRC, #(0 * 32)]
    ldp         q2,  q3, [SRC, #(1 * 32)]
    add         SRC, SRC, #64
    stp         q0,  q1, [DST, #(0 * 32)]
    stp         q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_fill_stp_x_aarch64
0:
    stp         x3,  x4, [DST, #(0 * 16)]
    stp         x5,  x6, [DST, #(1 * 16)]
    stp         x7,  x8, [DST, #(2 * 16)]
    stp         x9, x10, [DST, #(3 * 16)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_fill_stp_q_aarch64
0:
    stp         q0,  q1, [DST, #(0 * 32)]
    stp         q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_fill_stnp_x_aarch64
0:
    stnp        x3,  x4, [DST, #(0 * 16)]
    stnp        x5,  x6, [DST, #(1 * 16)]
    stnp        x7,  x8, [DST, #(2 * 16)]
    stnp        x9, x10, [DST, #(3 * 16)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_fill_stnp_q_aarch64
0:
    stnp        q0,  q1, [DST, #(0 * 32)]
    stnp        q2,  q3, [DST, #(1 * 32)]
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

asm_function aligned_block_copy_ld1st1_aarch64
0:
    ld1         {v0.16b, v1.16b, v2.16b, v3.16b}, [SRC]
    st1         {v0.16b, v1.16b, v2.16b, v3.16b}, [DST]
    add         SRC, SRC, #64
    add         DST, DST, #64
    subs        SIZE, SIZE, #64
    bgt         0b
    ret
.endfunc

#endif
