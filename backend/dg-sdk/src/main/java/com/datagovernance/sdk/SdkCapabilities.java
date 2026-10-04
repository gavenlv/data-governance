package com.datagovernance.sdk;

import java.util.List;

import com.datagovernance.model.CapabilityDescriptor;
import com.datagovernance.model.CapabilityProvider;
import org.springframework.stereotype.Component;

/** dg-sdk 的能力声明（骨架，未实现）。 */
@Component
public class SdkCapabilities implements CapabilityProvider {

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return List.of(
                CapabilityDescriptor.notImplemented("sdk.clients", "多语言客户端 SDK", "D8 治理运营",
                        "docs/09 §9.10", "Phase 1",
                        "由模型定义生成 Java / Python / TypeScript 客户端，保证与模型一致",
                        List.of("未实现", "Python 参考实现已有 dgctl 与 SDK 雏形",
                                "设计要点：SDK 由模型注册表代码生成，而非手工维护")),
                CapabilityDescriptor.notImplemented("sdk.embedded", "嵌入式组件（BI/IDE）", "D2 目录与发现",
                        "docs/14 §5", "Phase 2",
                        "只读资产卡：在 BI/IDE/数据平台内嵌展示治理信息与申请入口",
                        List.of("未实现", "设计要点：把治理信息放进用户已有的工作现场，而不是要求他们来平台")),
                CapabilityDescriptor.notImplemented("sdk.cli", "命令行 dgctl（Java 版）", "D8 治理运营",
                        "docs/09 §9.10", "Phase 1",
                        "与 HTTP API 一致的 CLI：doctor / init / collect / lineage / capabilities",
                        List.of("Java 侧未实现", "Python 参考实现的 dgctl 已覆盖大部分命令，可作行为基准")));
    }
}
