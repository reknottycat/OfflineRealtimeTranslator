# 模型下载来源核验

核验日期：**2026-11-09（北京时间）**。本目录保存实际 HTTP 采样证据和可复现探测脚本，固定来源与工件身份见 [`sources.json`](sources.json)。JSON 中带 `+00:00` 的时间字段使用 UTC。

## 可以据此确认什么

| 工件 | 固定字节数 | 核验结论 |
| --- | ---: | --- |
| Gemma 4 E2B `gemma-4-E2B-it.litertlm` | 2,588,147,712 | ModelScope 国内文件的大小与 SHA-256 与工程冻结值一致；resolve/API 首块通过，resolve 非零 4 MiB 分块通过。 |
| HY-MT2 1.8B `Hy-MT2-1.8B-Q4_K_M.gguf` | 1,133,080,448 | ModelScope 国内文件的大小与 SHA-256 与工程冻结值一致；resolve/API/HF Mirror 的同一非零 4 MiB 分块完全一致。 |
| LMT 1.7B `LMT-60-1.7B.Q4_K_M.gguf` | 1,282,439,264 | HF Mirror 的首块、非零 64 字节和非零 4 MiB 分块通过；本次未找到同一冻结 GGUF 的 ModelScope 副本。 |

ModelScope 的 HY 仓库是 **`Tencent-Hunyuan/Hy-MT2-1.8B-GGUF`**，不同于 Hugging Face 的 `tencent/Hy-MT2-1.8B-GGUF`。两个平台的 revision 也各自固定，不能直接复制 namespace 或 commit。

这些是**有限范围下载和平台元数据核验**，没有重新完整下载、散列计算全部数 GB 权重。平台报告的 SHA-256 与已冻结值一致，但采样不能替代客户端完成后的整文件 SHA-256 校验。`chunk_sha256` 只标识采样块；CDN 的普通 `ETag` 也不一定是文件 SHA-256。

探测从当前云端环境发出，**不是中国实体手机、运营商或家庭宽带测速**。记录的秒数只说明该次请求耗时，不能作为中国网络可达性、速度或长期可用性保证。

## 备用来源及实际限制

### 设置界面的 ASR 下载来源

以下是代码中的候选顺序，不代表本次对这些 ASR 工件做过网络测速或完整下载：

- Nemotron QNN 压缩包依次尝试 `gh-proxy.org`、`ghproxy.imciel.com`，然后尝试 GitHub release 原站。
- Nemotron 和 Qwen 多语 CPU 文件默认使用国内入口优先、Hugging Face 原站兜底；Qwen 先尝试 ModelScope，随后是 HF Mirror 和 Hugging Face。设置界面的多语 CPU 下载目前使用这一默认顺序。
- Qwen NPU 模型依次使用 ModelScope 的两个入口、HF Mirror 和 Hugging Face；词表使用 Qwen 多语模型的默认来源。
- GigaAM 的自动来源为 HF Mirror、Hugging Face；Vosk Small 的自动来源还包括 Hugging Face、alphacephei 和 GitHub。显式来源选择可以改变其优先顺序。

各来源仍需通过下载器的长度、Range 和固定 SHA-256 校验；来源列表不保证各入口在任意网络下可达。

- **ModelScope resolve 与 API 是同平台入口。** resolve 通常重定向到 `cdn-lfs-cn-1.modelscope.cn`；本次 Gemma 的两个入口都落到该 CDN。HY 的 API 响应最终域名仍为 `modelscope.cn`，不能据此视为独立存储。它们可能共享平台和 CDN 故障，不是两份独立可用性保证。
- **HF Mirror 成功响应后仍可能走国外 CDN。** 本次三模型的最终下载域名均为 `us.aws.cdn.hf.co`。必须检查最终文件响应，不能只探测 `hf-mirror.com` 首页或 DNS。
- **LMT 未找到可直接替换的 ModelScope GGUF。** 官方 OpenAPI 搜索 `LMT-60` 返回 8 个 `yvanluo` 仓库；实查 `yvanluo/LMT-60-1.7B` 只有原始 `model.safetensors` 等文件，没有 GGUF。不能用 safetensors 替代当前 llama.cpp 工件，也不能将未核实的同名 URL 作为成功来源。
- **不同量化不能混合续传。** `fss618/Hy-MT2-1.8B-GGUF` 的同名 Q4 文件为 1,133,080,480 字节，与冻结文件的大小、SHA-256 均不同，已排除。
- **不能只将 `.cn` 改成 `.ai`。** 本次两个固定 revision 路径替换为 `www.modelscope.ai` 后均为 404。这不证明国际站完全没有该模型，只说明这两个直接替换出的地址不可用；国际站需要独立核验目录和版本。

用户提供的代理前缀仅作为候选，实际 LMT 探测结果如下：

| 候选前缀 | 本次结果 | 客户端应如何处理 |
| --- | --- | --- |
| `https://gh-proxy.org/` | 跳转 `gh-proxy.com`；返回 195 字节的 `Site Unavailable` HTML。 | 与 `.com` 属于相同观测故障域；拒绝错误内容并切换。 |
| `https://gh-proxy.com/` | 普通 GET 是 200；首 64 字节 Range 甚至是 206，但总长为 195、类型为 HTML；非零 Range 是 416。 | **200/206 都不等于模型可用。** 必须检查内容类型、总长和精确 Range。 |
| `https://ghproxy.imciel.com/` | 403，`error code: 1010`。 | 该次失败，切换；不视为已验证可下载。 |

用户明确报告失效的 `ghfast.top`、`hub.gitmirror.com`、`gh.llkk.cc`、`github.moeyy.xyz` 已排除，没有额外探测。

## 复现采样

[`probe_sources.py`](probe_sources.py) 只用 Python 标准库和普通 HTTPS GET。默认请求 `bytes=0-63`；显式 `--chunk` 才请求 `bytes=4194304-8388607`（非零 4 MiB）。先核验 HTTP 206、Content-Range、固定总长、类型和长度，再有限读取。服务器忽略 Range 或返回错误元数据时，不会继续下载全模型。

```bash
# 只列出已记录来源，不发网络请求。
python docs/model-downloads/probe_sources.py --list

# 默认是 --quick；只采样 HY 的国内主入口 64 字节。
python docs/model-downloads/probe_sources.py --model hy --source hy-resolve

# 对同一入口采样非零 4 MiB 分块。
python docs/model-downloads/probe_sources.py --model hy --source hy-resolve --chunk

# 重现 LMT 的 HF Mirror 及用户指定候选代理的有限探测。
python docs/model-downloads/probe_sources.py --model lmt --include-candidate-proxies --quick
```

脚本顺序探测来源，默认连接/读取超时 30 秒，读取循环也有时间上限。`--timeout` 允许 1–60 秒；它不绕过网络限制，不携带账户 token，不更改代理环境。输出为 JSON，包含原始固定来源 URL、**最终 hostname**及限定白名单响应头，不保存带临时签名的 CDN 重定向 URL、Cookie 或认证头。每个响应最多读取所选块长加 1 字节，用于发现异常多发；不保存模型块，不做全模型自动下载。

首块还检查 `GGUF` 或 `LITERTLM` 魔数。采样通过只代表该次范围请求通过，整文件完成后仍必须比对 `sources.json` 中冻结的 SHA-256。

## 证据索引

| 文件 | 内容 |
| --- | --- |
| [`modelscope-exact-artifacts.json`](modelscope-exact-artifacts.json) | 两个 ModelScope 工件的官方文件元数据。 |
| [`modelscope-small-range-probes.json`](modelscope-small-range-probes.json) | 国内 resolve/API 的首 64 字节实测。 |
| [`hf-mirror-small-range-probes.json`](hf-mirror-small-range-probes.json) | 三模型 HF Mirror 首块、最终域名及正确总长。 |
| [`four-mib-range-probes.json`](four-mib-range-probes.json) | 5 个非零 4 MiB 采样及块 SHA-256。 |
| [`user-proxy-nonzero-range-probes.json`](user-proxy-nonzero-range-probes.json) | LMT 候选代理和 HF Mirror 非零范围实测。 |
| [`user-proxy-basic-probes.json`](user-proxy-basic-probes.json) | `.org/.com` 返回 HTML 的假成功证据。 |
| [`modelscope-search-and-international-probes.json`](modelscope-search-and-international-probes.json) | LMT 官方索引搜索及两次国际站路径核验。 |
| [`lmt-modelscope-candidate-files.json`](lmt-modelscope-candidate-files.json) | `yvanluo/LMT-60-1.7B` 文件清单，无 GGUF。 |

本目录只报告来源及传输采样证据，不声称完成手机推理、后台下载、真机断网恢复或完整模型质量验收。
