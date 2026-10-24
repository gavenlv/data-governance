package com.datagovernance.sdk;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/**
 * dg-sdk 的能力声明。
 *
 * <p>本模块的定位在 docs/09 §9.10 就写清了：**SDK 是访问平台的方式之一，不是第二个平台**。
 * 因此这里实现的是"传输 + 类型 + 命令行"，不含任何业务判定 ——
 * 一旦 SDK 自己判断"这个资产你能不能看"，它就必然与服务端的授权判定漂移，
 * 而漂移的 SDK 比没有 SDK 更危险。
 *
 * <p>{@code sdk.clients} 的原始名称是「多语言客户端 SDK」。当前只交付了 **Java**：
 * TypeScript 的类型已由模型代码生成覆盖（`core.codegen`），但**没有** TypeScript 运行时客户端；
 * Python 侧的参考实现自带 HTTP 调用，也不是本 SDK。
 * 因此把名称改成「Java 客户端 SDK」，并在 notes 里写清另外两种语言的真实状态 ——
 * 名称比 notes 更容易被引用，所以名称必须与事实一致。
 */
@Component
public class SdkCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.implemented("sdk.clients", "Java 客户端 SDK", "D8 治理运营",
                        "docs/09 §9.10",
                        "`DgClient`（java.net.http + Jackson）：能力清单 / 资产列表与详情 / 全文检索 / "
                                + "血缘图 / 影响分析 / 采集健康度 + 原样 JSON 透传；Bearer 鉴权、超时、"
                                + "401·403·404 与连接失败可区分、不做隐式重试（写操作不幂等）",
                        List.of("已实现：与 `dgctl` 共用同一传输层 —— CLI 能做的，SDK 就能做，不会漂移",
                                "已实现：未知字段不报错（向前兼容），错误响应把平台的 hint 原样带出",
                                "**未实现：TypeScript / Python 客户端** —— TypeScript 类型由 `core.codegen` "
                                        + "覆盖，但没有运行时客户端；Python 侧是参考实现自带的调用，不属本 SDK",
                                "**未实现：由模型自动生成的类型** —— 当前 DTO 只固化形状稳定的读接口，"
                                        + "详情/影响分析返回原始 JSON：契约未稳定的部分过早固化只会制造假 API")),
                CapabilityDescriptor.implemented("sdk.cli", "命令行 dgctl（Java 版）", "D8 治理运营",
                        "docs/09 §9.10",
                        "与 HTTP API 一致：doctor / capabilities / search / lineage / asset / impact / "
                                + "collect-health；`--json` 输出原始 JSON；退出码语义稳定"
                                + "（0 成功 / 1 平台错误 / 2 用法错误 / 3 连接失败）",
                        List.of("已实现：`java -jar dg-api-0.1.0.jar dgctl <命令>` —— "
                                        + "**CLI 与控制面同一个产物**，不会出现 CLI 版本与服务端 API 不匹配",
                                "已实现：CLI 不启动 Spring 容器（不连库、不建 Web 服务、不跑迁移），"
                                        + "因此「数据库连不上」时诊断命令仍然可用 —— 那正是它要诊断的场景",
                                "已实现：端到端验证覆盖 doctor / capabilities / search / lineage / asset / "
                                        + "错误令牌(1) / 用法错误(2) / 连接失败(3)（tools/sdk_cli_verify.py）",
                                "**未实现**：init / codegen（模型工具链仍在 Python 侧，见 `core.codegen` 的说明）、"
                                        + "交互式 shell、写命令（采集触发与审批走 HTTP，不在本轮 CLI 范围）")),
                CapabilityDescriptor.implemented("sdk.embedded", "嵌入式组件（BI/IDE）", "D2 目录与发现",
                        "docs/14 §5",
                        "只读资产卡：`POST /api/v1/embed/token` 用真实主体判定可见性后签发"
                                + "**短期、单资产、只读**令牌；`GET /api/v1/embed/assets/{urn}?t=...` "
                                + "无需平台登录，但只返回**字段白名单**（名称/类型/分级/生命周期/Owner/描述/链接）；"
                                + "SPA 提供 `/embed/:urn` 紧凑卡片，可直接 iframe 引用",
                        List.of("已实现：**独立只读端点**，不复用管理接口 —— 否则「嵌入」会变成绕过权限的入口",
                                "已实现：令牌 HMAC-SHA256 签名、绑定单个 URN、默认 5 分钟（上限 1 小时）、"
                                        + "密钥从 DG_SECRET_KEY 派生（带 context，不与数据源加密共用）；未配置密钥一律 502，绝不降级成不签名",
                                "已实现：字段白名单显式列出 —— **不包含** schema 明细、连接信息、版本历史、审计记录；"
                                        + "新增字段必须显式加进白名单",
                                "已实现：可见性在签发时用真实主体判定（不可见按 404 处理，不泄漏资产存在性）",
                                "**未实现**：嵌入卡的自定义主题/品牌、多资产列表卡、BI 工具内的深度链接回写")));
    }
}
