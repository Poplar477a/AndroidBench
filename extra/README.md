# extra —— 与主工程无关或已归档的文件

本目录用于把项目根目录"多余的东西"集中到一处。主工程只依赖这些以外的内容：
app/ AndroidBench/ tools/ docs/ gradle/ dist/ 以及构建配置与 keystore。

## 内容说明

| 条目 | 是什么 | 能否删除 |
|---|---|---|
| qpm-*.cmd | QPM（WSL 交互 shell / 产品列表）的 Windows 批处理，与本项目无关。**已移出版本库**（仅本地保留，.gitignore 排除），公开仓库不再带这些文件 | 可删（不属于本项目） |
| .deliver/ | 早期交付快照（UFSBenchmark/ 副本 + UFSBenchmark-source-20261012.zip） | 可删（历史快照） |
| .buildlogs/ | 早期构建日志 | 可删 |
| .spec2017-iso/ .spec2017-raw/ .spec2017-out/ .spec2017-req/ | SPEC2017 接入过程中的中间产物（空的/清单/输出）。注意 tools/spec2017 里的脚本默认仍写 .spec2017-out、.spec2017-req，会在根目录按需重建 | 可删（可重建） |
| CDM-HOMOLAB.7z | 用户自己的归档（与本项目无关） | 用户自定 |
| dynamo_bench | x86_64 的宿主构建产物（早期调试用） | 可删 |
| probe_ok/ | 早期探针留下的空目录 | 可删 |
| ref_zip/ | docs/如何测试顺序写入.zip 的解包内容（fio 作业文件） | 可删（zip 原件在 docs/） |
| 4k level+mix lite.fio | 用户的 fio 作业文件（与 ref_zip 里那批同源） | 用户自定 |

## 仍然留在根目录、**不要**移动的东西

| 条目 | 原因 |
|---|---|
| app/ AndroidBench/ tools/ docs/ gradle/ dist/ | 主工程与交付物 |
| build.sh build.gradle.kts settings.gradle.kts gradle.properties | 构建入口 |
| .tools/ | Android SDK / NDK / JDK / Gradle（build.sh 里写死了 .tools/jdk-17 等路径） |
| .gradle/ | Gradle 构建缓存 |
| .build.lock | build.sh 的 flock 锁文件（路径写死） |
| keystore/ keystore.properties local.properties | 签名与 SDK 路径，构建必需 |
| SPEC2017/ | SPEC CPU2017 安装介质解包树（tools/spec2017 流水线的输入，4.1 GB，已 gitignore） |
| .evidence/ | 逐轮实测证据归档（docs 里有引用，372 MB，已 gitignore） |

