# BYOB 组件包打包规范

**中文** · [English](byob-打包规范.en.md)


> 与 App 内「终端 → spec」命令打印的内容同源（改一处必须同步另一处）。
> 用法：终端页「导入组件包(.zip)」按钮 —— 选 .zip 即完整组件包，选裸 ELF 则自动打成最小组件包。

~~~
组件包打包规范（BYOB）
========================================
【方式一】最简：直接导入一个裸 ELF
  · 要求：aarch64（e_machine=0xB7）可执行文件；依赖只能是 bionic 白名单
    （libc.so / libm.so / libdl.so / liblog.so / libz.so / libstdc++.so /
      libc++_shared.so / libandroid.so / libatomic.so）；
    glibc 名字（libc.so.6、ld-linux-aarch64.so.1 等）会被直接拒绝。
  · 行为：应用自动生成最小组件包（无参数、不做参考输出判分），id 取文件名。
  · 适用：自己写的小程序、单文件微基准。

【方式二】完整：.zip 组件包（支持传参 + 与官方参考输出比对判分）
  目录结构（zip 内即根目录，不要再套一层）：
    runner.json             必需，见下
    bin/<可执行文件>         必需，arm64 ELF
    data/input/...          可选，输入数据集
    data/output/<参考输出>   可选，判分用的参考输出

  runner.json 字段：
    benchmark_id            字符串，唯一用例 id
    binary_path             相对路径，如 bin/lbm
    command_args            字符串数组；以 data/ 开头的元素会被映射为包内绝对路径
    stdout_file             标准输出落盘的文件名（**不要**用程序自己会写的名字）
    oracle_rule {
      type                  none 表示不判分；其它值按下面两个容差判分
      reference_file        相对路径，如 data/output/lbm.out
      abs_tolerance         绝对容差，如 1e-7
      rel_tolerance         相对容差，如 1e-4
      ignore_line_patterns  命中任一子串的行两侧都不参与比对
                            （时间/内存分配这类每次运行都不同的行必须排除）
    }
    execution_profile {
      affinity_policy          预留，当前用 none
      cooldown_target_celsius  冷却目标温度，0 = 不等冷却
      max_execution_seconds    墙钟上限
    }

【判分口径】逐行比对：行内按空白切 token；两侧都能解析为数字时用
  |a-b| <= abs_tol + rel_tol*|b|，否则按字符串精确比对；空行两侧都忽略。
  这是"与参考输出一致"的一致性判定，不代表任何官方成绩。

【SPEC 相关：严谨声明】
  · 本终端与"常规用 adb 把 SPEC 官方二进制推进手机跑"**不是一回事**：官方口径要求 SPEC 官方
    工具链、官方 flags（base/peak 分档）、官方输入集与官方 run rules；本通路是"用户自备源码 +
    本地编译 + 与参考输出做一致性比对"，两者不可互换、不可类比，成绩也不得与官方比较。
  · 判分只有两种含义：输出与参考一致 / 不一致。本通路**不产出任何 SPEC 指标**，不得称为
    SPEC 成绩、SPECratio、SPECspeed、SPECrate，也不得与 spec.org 公布的任何数值比较或排名。
  · 编译加 -DSPEC 只是为了**输出保真**（让程序输出与官方参考一致，否则无从比对），
    不代表获得官方编译口径或官方成绩。
  · 本项目不分发 SPEC 的任何源码 / 输入集 / 参考输出 / 官方工具链；组件包须由用户用自备介质生成。

【打包示例】519.lbm_r.zip 内：
    runner.json  benchmark_id=519.lbm_r / binary_path=bin/lbm
                 command_args=[20, reference.dat, 0, 1, 100_100_130_cf_a.of]
                 stdout_file=lbm.out
                 oracle_rule={type=numeric_tolerance, reference_file=data/output/lbm.out,
                              abs_tolerance=1e-7, rel_tolerance=1e-4}
  输入文件 100_100_130_cf_a.of 放包根（程序按裸文件名打开，需位于运行目录）
~~~
