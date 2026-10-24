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
                CapabilityDescriptor.notImplemented("sdk.embedded", "嵌入式组件（BI/IDE）", "D2 目录与发现",
                        "docs/14 §5", "Phase 2",
                        "只读资产卡：在 BI/IDE/数据平台内嵌展示治理信息与申请入口",
                        List.of("未实现",
                                "设计要点：把治理信息放进用户已有的工作现场，而不是要求他们来平台",
                                "已知风险（实现时必须解决）：嵌入卡片要独立的只读端点，"
                                        + "不能复用管理接口 —— 否则「嵌入」会变成绕过权限的入口")));
    }
}
