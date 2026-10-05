package com.datagovernance.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.datagovernance.api.security.Subjects;
import com.datagovernance.ingestion.edge.EdgeAgentService;
import com.datagovernance.policy.AccessPolicy;
import com.datagovernance.policy.Subject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Edge Agent 接入（ADR-012 的推模式）。
 *
 * <p>两类接口，<b>认证方式不同</b>，不要混淆：
 * <ul>
 *   <li>{@code /api/v1/edge/agents*}：<b>平台令牌</b>（人来管理 Agent 注册与吊销），
 *       需 {@code governance:write}；</li>
 *   <li>{@code /api/v1/edge/agent/*}：<b>Agent 自己的凭据</b>（{@code dgagent_...}），
 *       由数据侧采集器调用，只允许心跳与上报。</li>
 * </ul>
 *
 * <p>后者虽然不走平台令牌，但凭据是注册时一次性下发、库里只存哈希、可吊销，
 * 且上报的数据同样受来源保护（AUTO_COLLECTED）—— 推模式不是绕过治理的侧路。
 */
@RestController
@RequestMapping("/api/v1/edge")
public class EdgeController {

    private final EdgeAgentService agents;

    public EdgeController(EdgeAgentService agents) {
        this.agents = agents;
    }

    // ------------------------------------------------- 管理面（平台令牌）

    /** 注册 Agent：返回**一次性**凭据。 */
    @PostMapping("/agents")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        @SuppressWarnings("unchecked")
        List<String> capabilities = input.get("capabilities") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return agents.register(str(input.get("agentId")), str(input.get("displayName")),
                str(input.get("namespace")), capabilities, str(input.get("version")), subject.id());
    }

    @GetMapping("/agents")
    public Map<String, Object> listAgents() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = agents.listAgents();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("agents", rows);
        payload.put("note", "seconds_since_heartbeat 是判断 Agent 是否存活的主要信号；"
                + "ADR-012 选型的 Go 单二进制 Agent 本体**未实现**，本接口与上报接口是其协议接入点");
        return payload;
    }

    /** 吊销 Agent 凭据（立即生效，保留历史上报记录）。 */
    @PostMapping("/agents/{agentId}/revoke")
    public Map<String, Object> revoke(@PathVariable String agentId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "governance:write");
        Map<String, Object> input = body == null ? Map.of() : body;
        return agents.revoke(agentId, subject.id(), str(input.get("reason")));
    }

    /** 上报记录（谁在什么时候推了多少实体、有没有被拒）。 */
    @GetMapping("/reports")
    public Map<String, Object> reports() {
        Subject subject = Subjects.require();
        AccessPolicy.authorize(subject, "asset:read");
        List<Map<String, Object>> rows = agents.recentReports(100);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", rows.size());
        payload.put("reports", rows);
        payload.put("note", "被拒的上报会带 reject_reason：护栏拒绝时**不写入、也不截断**，"
                + "这样才能从记录上区分「没推」和「推了但被拒」");
        return payload;
    }

    // ------------------------------------------------- Agent 面（Agent 凭据）

    /** Agent 心跳（凭据放 {@code Authorization: Bearer dgagent_...}）。 */
    @PostMapping("/agent/heartbeat")
    public Map<String, Object> heartbeat(HttpServletRequest request,
                                        @RequestBody(required = false) Map<String, Object> body) {
        return agents.heartbeat(agentToken(request), body);
    }

    /** Agent 上报数据集与列。 */
    @PostMapping("/agent/report")
    public Map<String, Object> report(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return agents.reportDatasets(agentToken(request), body == null ? Map.of() : body);
    }

    /** Agent 自检：凭据是否有效、属于哪个命名空间（部署排障时先看这个）。 */
    @GetMapping("/agent/whoami")
    public Map<String, Object> whoami(HttpServletRequest request) {
        Map<String, Object> agent = agents.authenticate(agentToken(request));
        if (agent == null) {
            throw EdgeAgentService.EdgeException.unauthorized(
                    "Agent 凭据无效或已吊销（Authorization: Bearer dgagent_...）");
        }
        return Map.of("agentId", agent.get("agent_id"), "namespace", agent.get("namespace"),
                "capabilities", agent.get("capabilities") == null ? List.of() : agent.get("capabilities"));
    }

    private static String agentToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
