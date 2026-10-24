package com.datagovernance.lineage;

import java.util.List;

import com.datagovernance.lineage.LineagePathService.Hop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径追踪的纯逻辑测试（BFS）。
 *
 * <p>SQL 负责"取哪些边"，BFS 负责"怎么连起来"。后者是纯函数，也是最容易写错的部分
 * （方向、去环、不可达与空路径的区分），因此单独测。
 */
class LineagePathServiceTest {

    private static Hop hop(String from, String to, long id) {
        return new Hop(from, to, id, "sql_parse", 0.8, "exact", "DIRECT");
    }

    @Test
    void findsShortestChain() {
        List<Hop> edges = List.of(
                hop("a", "b", 1), hop("b", "c", 2), hop("a", "d", 3), hop("d", "c", 4));

        List<Hop> path = LineagePathService.findPath(edges, "a", "c");

        assertEquals(2, path.size(), "应该选更短的那条（a→b→c），而不是先遇到的 a→d→c");
        assertEquals("b", path.get(0).toUrn());
        assertEquals("c", path.get(1).toUrn());
    }

    @Test
    void returnsSingleHopWhenAdjacent() {
        List<Hop> path = LineagePathService.findPath(List.of(hop("a", "b", 1)), "a", "b");
        assertEquals(1, path.size());
    }

    @Test
    void distinguishesUnreachableFromTrivialCases() {
        List<Hop> edges = List.of(hop("a", "b", 1));
        assertNull(LineagePathService.findPath(edges, "a", "z"), "不可达必须是 null，不能是空列表");
        assertNull(LineagePathService.findPath(edges, "a", "a"), "起终点相同不代表有路径");
        assertNull(LineagePathService.findPath(List.of(), "a", "b"));
        assertNull(LineagePathService.findPath(edges, null, "b"));
    }

    @Test
    void handlesCyclesWithoutLoopingForever() {
        List<Hop> edges = List.of(
                hop("a", "b", 1), hop("b", "c", 2), hop("c", "a", 3), hop("c", "d", 4));

        List<Hop> path = LineagePathService.findPath(edges, "a", "d");

        assertEquals(3, path.size(), "环不应导致重复节点或死循环");
        assertEquals("d", path.get(2).toUrn());
    }

    @Test
    void walksOnlyForwardDirection() {
        List<Hop> edges = List.of(hop("b", "a", 1));
        assertTrue(LineagePathService.findPath(edges, "a", "b") == null,
                "方向固定为上游→下游：反向边不应被当成 a→b");
    }
}
