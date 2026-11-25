package com.metric.mcp.service;

import com.metric.mcp.config.McpServerEndpointConfig;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成「MCP 接入包」：用户下载后按 README 填地址和令牌即可接入。
 *
 * 包内容：
 *   README.md              —— 中文接入说明与排错
 *   mcp-config.json        —— 支持远程 MCP 的客户端直接粘贴
 *   metric_mcp_bridge.py   —— 只支持 stdio 的客户端用的零依赖桥接器
 *
 * 注意：包里的令牌是创建时的明文，因此本接口只在「刚创建令牌」时调用；
 * 已存在的令牌无法再导出明文（库里只有哈希）。
 */
@Service
public class McpPackageService {

    private static final Map<String, List<String>> TOOL_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> TITLES = Map.of(
            McpServers.ALARM, "告警",
            McpServers.GONGDAN, "工单",
            McpServers.ISSUE, "问题");

    static {
        TOOL_NAMES.put(McpServers.ALARM, List.of(
                "alarm_list_systems — 系统清单（把「BOMC 系统」解析成 systemId）",
                "alarm_list_categories — 指标大类/小类清单（把「性能问题」解析成 categoryId）",
                "alarm_search — 按系统/大类/级别/状态/关键词/时间查告警明细",
                "alarm_summary — 按系统/大类/级别等维度聚合告警数量"));
        TOOL_NAMES.put(McpServers.GONGDAN, List.of(
                "gongdan_search — 按关键词/关闭状态/等级/BOMC 状态查工单",
                "gongdan_get — 查单条工单详情（含正文与 BOMC 推送情况）",
                "gongdan_summary — 工单维度统计与平均闭环时长"));
        TOOL_NAMES.put(McpServers.ISSUE, List.of(
                "issue_search — 按关键词/解决状态/类型/来源/时间查问题",
                "issue_get — 查单条问题详情",
                "issue_summary — 问题维度统计与超期未解决数量"));
    }

    public byte[] buildPackage(String serverKey, String publicUrl, String plaintextToken) {
        if (!McpServers.isValid(serverKey)) {
            throw new IllegalArgumentException("未知的 MCP 服务：" + serverKey);
        }
        String title = TITLES.getOrDefault(serverKey, serverKey);
        String url = publicUrl + McpServerEndpointConfig.endpointOf(serverKey);

        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {

            put(zip, "README.md", readme(serverKey, title, url, plaintextToken));
            put(zip, "mcp-config.json", mcpConfig(serverKey, title, url, plaintextToken));
            put(zip, "metric_mcp_bridge.py", bridgeScript());

            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成接入包失败：" + e.getMessage(), e);
        }
    }

    public String suggestedFileName(String serverKey) {
        return "metric-mcp-" + serverKey + ".zip";
    }

    // ------------------------------------------------------------------ 包内文件

    private String readme(String serverKey, String title, String url, String token) {
        StringBuilder tools = new StringBuilder();
        for (String tool : TOOL_NAMES.getOrDefault(serverKey, List.of())) {
            String[] parts = tool.split(" — ", 2);
            tools.append("- `").append(parts[0]).append("` ")
                    .append(parts.length > 1 ? parts[1] : "").append("\n");
        }
        return """
                # metric-code %s MCP 接入包

                ## 这是什么

                指标体系平台提供的**只读** MCP 服务：内网智能体接入后，可以直接用自然语言问
                「最近有哪些告警」「工单积压在哪一步」这类问题。服务不提供任何新建、修改、删除能力。

                ## 端点与令牌

                - 端点：`%s`
                - 令牌：`%s`

                令牌只在创建时显示这一次，请立刻保存。平台侧只存哈希，丢了只能重新创建。
                令牌默认长期有效，可在平台「技能管理 → MCP 管理」里随时吊销。

                ## 方式 A：客户端支持远程 MCP（推荐）

                把下面这段合并进客户端的 MCP 配置（`mcpServers` 节点下增加一项）：

                ```json
                {
                  "mcpServers": {
                    "metric-%s": {
                      "url": "%s",
                      "headers": { "Authorization": "Bearer %s" }
                    }
                  }
                }
                ```

                同目录的 `mcp-config.json` 就是这段内容，可直接复制。

                ## 方式 B：客户端只支持 stdio

                有些客户端只能拉起本地进程。用同目录的 `metric_mcp_bridge.py` 转发：

                ```json
                {
                  "mcpServers": {
                    "metric-%s": {
                      "command": "python",
                      "args": [
                        "/绝对路径/metric_mcp_bridge.py",
                        "--url", "%s",
                        "--token", "%s"
                      ]
                    }
                  }
                }
                ```

                要求客户端所在机器有 **Python 3.8+**（只用标准库，不需要 pip 安装任何东西，
                适合内网无外网环境）。也可以用 `python3` 或 `py -3` 替换 `python`。

                ## 可用工具

                %s
                所有查询都有限制：单次最多 50 条（默认 20），时间窗口默认近 7 天、上限 180 天。

                ## 排错

                | 现象 | 原因 | 处理 |
                | --- | --- | --- |
                | 401 | 令牌错误、已吊销或已过期 | 在 MCP 管理页核对令牌，或重新创建 |
                | 404 | 服务未开启 | 让平台管理员在「MCP 管理」里打开对应服务的开关 |
                | 连接超时 | 网络策略未开通 | 申请开通到平台地址的访问策略 |
                | `limit 上限为 50` | 请求条数超限 | 缩小时间范围或分页查询 |

                ## 数据边界

                服务只返回业务必要字段：工单正文、告警内容、问题描述均做了截断，
                附件路径、BOMC 推送快照等内部字段不下发。
                """.formatted(title, url, token, serverKey, url, token,
                serverKey, url, token, tools);
    }

    private String mcpConfig(String serverKey, String title, String url, String token) {
        return """
                {
                  "mcpServers": {
                    "metric-%s": {
                      "url": "%s",
                      "headers": {
                        "Authorization": "Bearer %s"
                      }
                    }
                  }
                }
                """.formatted(serverKey, url, token);
    }

    private String bridgeScript() {
        ClassPathResource resource = new ClassPathResource("mcp-bridge/metric_mcp_bridge.py");
        try (InputStream input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            // Windows 工作区是 CRLF，打进 jar 后如果原样下发给 Linux，
            // shebang 会变成 "python3\r" 导致直接执行报 bad interpreter。
            // 统一转成 LF，保证包在两边都能用。
            return content.replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("读取桥接器脚本失败：" + e.getMessage(), e);
        }
    }

    private void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
