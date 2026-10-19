package com.project.domain.map.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.domain.common.MapStatus;
import com.project.domain.flight.service.FlightDBAdaptor;
import com.project.domain.map.component.GraphCache;
import com.project.domain.map.component.UsageManager;
import com.project.domain.map.entity.Edge;
import com.project.domain.map.entity.Node;
import com.project.domain.map.repository.MapInfoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MapServiceLooplessPathTest {

    @Test
    void spurSearchCannotRevisitRootNodes() {
        Node spur = node(2L, "spur");
        Node root = node(1L, "root");
        Node detour = node(3L, "detour");
        Node end = node(4L, "end");
        Edge backToRoot = edge(21L, spur, root);
        Edge directToEnd = edge(24L, spur, end);
        Edge onward = edge(22L, root, detour);
        Edge finish = edge(23L, detour, end);

        GraphCache graphCache = mock(GraphCache.class);
        when(graphCache.getEdges(spur)).thenReturn(List.of(backToRoot, directToEnd));
        when(graphCache.getEdges(root)).thenReturn(List.of(onward));
        when(graphCache.getEdges(detour)).thenReturn(List.of(finish));
        MapInfoRepository mapInfoRepository = mock(MapInfoRepository.class);
        when(mapInfoRepository.findByBasicMapTrue()).thenReturn(Optional.empty());
        MapService mapService = new MapService(graphCache, new UsageManager(), mock(MapDBAdaptor.class),
                mock(FlightDBAdaptor.class), mapInfoRepository, new ObjectMapper());

        @SuppressWarnings("unchecked")
        List<Edge> path = (List<Edge>) ReflectionTestUtils.invokeMethod(mapService, "findSinglePath", spur, end,
                Set.of(), Set.of(root.getId()));

        assertThat(path).containsExactly(directToEnd);
        verify(mapInfoRepository).findByBasicMapTrue();
    }

    private static Node node(Long id, String code) {
        return Node.builder().id(id).nodeCode(code).posX(id.doubleValue()).posY(0.0).status(MapStatus.AVAILABLE)
                .build();
    }

    private static Edge edge(Long id, Node source, Node destination) {
        return Edge.builder().id(id).edgeCode("edge-" + id).srcNode(source).dstNode(destination)
                .distance(1.0).travelTime(1.0).status(MapStatus.AVAILABLE).build();
    }
}
