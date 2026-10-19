package com.project.domain.map.service;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.project.domain.map.repository.MapInfoRepository;
import org.springframework.stereotype.Service;

import com.project.domain.common.MapStatus;
import com.project.domain.map.component.GraphCache;
import com.project.domain.map.component.UsageManager;
import com.project.domain.map.dto.MapResponse;
import com.project.domain.map.entity.Edge;
import com.project.domain.map.entity.MapInfo;
import com.project.domain.map.entity.Node;
import com.project.domain.mission.dto.MissionWebSocketDtos.PathOptionDto;
import com.project.domain.flight.entity.Flight;
import com.project.domain.flight.service.FlightDBAdaptor;
import com.project.domain.towingcar.entity.TowingCar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지도 및 경로 탐색 관련 비즈니스 로직을 담당하는 서비스 클래스
 * A*, Yen's 알고리즘 등을 사용하여 최적 경로를 계산합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MapService {

    private final GraphCache graphCache;
    private final UsageManager usageManager;
    private final MapDBAdaptor mapDBAdaptor;
    private final FlightDBAdaptor flightDBAdaptor;
    private final MapInfoRepository mapInfoRepository;
    private final ObjectMapper objectMapper;

    @AllArgsConstructor
    @Getter
    static class PathNode {
        Node node;
        double g;
        double f;
    }

    /**
     * A* Algorithm using GraphCache and UsageManager
     */

    /**
     * [New] Calculate Pushback Path using actual car position
     */
    public Map<String, Object> getPushbackPath(Long flightId, String targetGate) {
        Flight flight = flightDBAdaptor.getFlightById(flightId);
        TowingCar car = flight.getAssignedTowingCar();

        Node startNode;
        if (car != null) {
            startNode = findNearestNode(car.getLastPosX(), car.getLastPosY());
        } else {
            startNode = mapDBAdaptor.getNodeByCode(flight.getNodeCode());
        }

        Node endNode = mapDBAdaptor.getNodeByCode(targetGate);

        List<PathOptionDto> pathOptions = findShortestPath(startNode, endNode);

        if (pathOptions.isEmpty()) {
            return Map.of(
                    "destNodeName", targetGate,
                    "path", List.of());
        }

        // Return best path's edge IDs
        return Map.of(
                "destNodeName", targetGate,
                "path", pathOptions.get(0).getEdgeIds());
    }

    /**
     * [Legacy Support] 최단 경로 1개 반환 (A* for Towing Service)
     */
    public List<Edge> findOptimalPath(Node start, Node end) {
        return findSinglePath(start, end, Collections.emptySet());
    }

    /**
     * [Helper] Edge List -> MQTT Payload List 변환
     */
    public List<Map<String, Object>> convertPathToPayload(List<Edge> path) {
        List<Map<String, Object>> payload = new ArrayList<>();

        for (Edge edge : path) {
            // 1. 중간 경로점(Waypoints) 추가
            if (edge.getWaypoints() != null && !edge.getWaypoints().isEmpty()) {
                try {
                    List<MapResponse.PointDto> waypoints = objectMapper.readValue(
                            edge.getWaypoints(),
                            new TypeReference<List<MapResponse.PointDto>>() {
                            });

                    for (MapResponse.PointDto wp : waypoints) {
                        Map<String, Object> point = new HashMap<>();
                        point.put("nodeId", null); // 중간 점은 노드 ID가 없음
                        point.put("x", wp.getX());
                        point.put("y", wp.getY());
                        point.put("edgeId", edge.getEdgeCode());
                        point.put("maxSpeed", edge.getMaxSpeed() != null ? (double) edge.getMaxSpeed() : 10.0);
                        payload.add(point);
                    }
                } catch (Exception e) {
                    // 로그만 남기고 다음 노드로 진행
                    log.error("Failed to parse waypoints for edge {}: {}", edge.getEdgeCode(), e.getMessage());
                }
            }

            // 2. 도착 노드(Target Node) 추가
            Map<String, Object> targetPoint = new HashMap<>();
            Node targetNode = edge.getDstNode();

            targetPoint.put("nodeId", targetNode.getNodeCode());
            targetPoint.put("x", targetNode.getPosX());
            targetPoint.put("y", targetNode.getPosY());
            targetPoint.put("edgeId", edge.getEdgeCode());
            targetPoint.put("maxSpeed", edge.getMaxSpeed() != null ? (double) edge.getMaxSpeed() : 10.0);

            payload.add(targetPoint);
        }
        return payload;
    }

    /**
     * Yen's Algorithm for K-Shortest Paths (K=3)
     */
    public List<PathOptionDto> findShortestPath(Node start, Node end) {
        int K = 3;
        List<List<Edge>> resultPaths = new ArrayList<>();

        // 1. First shortest path
        List<Edge> firstPath = findSinglePath(start, end, Collections.emptySet());
        if (firstPath.isEmpty()) {
            return Collections.emptyList();
        }
        resultPaths.add(firstPath);

        PriorityQueue<List<Edge>> candidates = new PriorityQueue<>(
                Comparator.comparingDouble(this::calculatePathCost));

        // 2. Yen's Iteration
        for (int k = 1; k < K; k++) {
            List<Edge> prevPath = resultPaths.get(k - 1);

            // Spur Node Loop (iterate over all nodes in the path except the last
            // implementation detail)
            // Here simpler: iterate edges
            List<Edge> rootPath = new ArrayList<>();

            for (int i = 0; i < prevPath.size(); i++) {
                Edge spurEdge = prevPath.get(i);
                Node spurNode = spurEdge.getSrcNode();

                // Root path is path from start to spurNode
                if (i > 0)
                    rootPath.add(prevPath.get(i - 1));

                Set<Long> excluded = new HashSet<>();
                Set<Long> excludedNodes = new HashSet<>();
                for (Edge rootEdge : rootPath) {
                    excludedNodes.add(rootEdge.getSrcNode().getId());
                }
                // Block edges used in previous k paths that share the same root path
                for (List<Edge> p : resultPaths) {
                    if (isSameRoot(p, rootPath, i) && i < p.size()) {
                        excluded.add(p.get(i).getId());
                    }
                }

                // Block the current spur edge for this specific deviation attempt
                excluded.add(spurEdge.getId());

                // Calculate spur path from spurNode to end
                List<Edge> spurPath = findSinglePath(spurNode, end, excluded, excludedNodes);

                if (!spurPath.isEmpty()) {
                    List<Edge> totalPath = new ArrayList<>(rootPath);
                    totalPath.addAll(spurPath);
                    if (!containsPath(candidates, resultPaths, totalPath)) {
                        candidates.add(totalPath);
                    }
                }
            }

            if (candidates.isEmpty())
                break;
            resultPaths.add(candidates.poll());
        }

        // Convert to DTOs
        List<PathOptionDto> dtos = new ArrayList<>();
        for (int i = 0; i < resultPaths.size(); i++) {
            List<Edge> p = resultPaths.get(i);
            LinkedList<String> edgeIds = new LinkedList<>();
            for (Edge e : p)
                edgeIds.add(e.getEdgeCode());

            dtos.add(PathOptionDto.builder()
                    .optionId((long) (i + 1))
                    .label(i == 0 ? "최적 경로" : "대안 경로 " + i)
                    .edgeIds(edgeIds)
                    .build());
        }
        return dtos;
    }

    private boolean isSameRoot(List<Edge> path, List<Edge> root, int index) {
        if (path.size() < index)
            return false;
        for (int j = 0; j < index; j++) {
            if (!path.get(j).getId().equals(root.get(j).getId()))
                return false;
        }
        return true;
    }

    private boolean containsPath(PriorityQueue<List<Edge>> candidates, List<List<Edge>> results, List<Edge> target) {
        for (List<Edge> p : candidates)
            if (isSamePath(p, target))
                return true;
        for (List<Edge> p : results)
            if (isSamePath(p, target))
                return true;
        return false;
    }

    private boolean isSamePath(List<Edge> p1, List<Edge> p2) {
        if (p1.size() != p2.size())
            return false;
        for (int i = 0; i < p1.size(); i++) {
            if (!p1.get(i).getId().equals(p2.get(i).getId()))
                return false;
        }
        return true;
    }

    private double calculatePathCost(List<Edge> path) {
        return path.stream().mapToDouble(this::edgeCost).sum();
    }

    private double edgeCost(Edge edge) {
        return edge.getTravelTime() != null ? edge.getTravelTime() : edge.getDistance() / 10.0;
    }

    /**
     * Modified A* Algorithm (Supports excluded edges)
     */
    private List<Edge> findSinglePath(Node start, Node end, Set<Long> excludedEdges) {
        return findSinglePath(start, end, excludedEdges, Collections.emptySet());
    }

    private List<Edge> findSinglePath(Node start, Node end, Set<Long> excludedEdges, Set<Long> excludedNodes) {
        double maxSpeed = mapInfoRepository.findByBasicMapTrue()
                .map(MapInfo::getMaxSpeed)
                .filter(speed -> speed != null && Double.isFinite(speed) && speed > 0.0)
                .orElse(10.0);
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(
                Comparator.comparingDouble(PathNode::getF));
        Map<Long, Double> gScore = new HashMap<>();
        Map<Long, Edge> cameFrom = new HashMap<>();
        Set<Long> closedSet = new HashSet<>();

        gScore.put(start.getId(), 0.0);
        openSet.add(new PathNode(start, 0.0, heuristic(start, end, maxSpeed)));

        while (!openSet.isEmpty()) {
            PathNode current = openSet.poll();
            Node currentNode = current.node;

            if (currentNode.getId().equals(end.getId())) {
                return reconstructEdgeList(cameFrom, currentNode);
            }

            if (closedSet.contains(currentNode.getId()))
                continue;
            closedSet.add(currentNode.getId());

            for (Edge edge : graphCache.getEdges(currentNode)) {
                if (excludedEdges.contains(edge.getId()))
                    continue;

                Node neighbor = edge.getDstNode();
                if (excludedNodes.contains(neighbor.getId()))
                    continue;
                if (closedSet.contains(neighbor.getId()))
                    continue;
                if (edge.getStatus() == MapStatus.BLOCKED || neighbor.getStatus() == MapStatus.BLOCKED)
                    continue;

                // Usage Check (Global lock)
                if (usageManager.isEdgeLocked(edge.getId()) || usageManager.isNodeLocked(neighbor.getId()))
                    continue;

                double newG = gScore.getOrDefault(currentNode.getId(), Double.MAX_VALUE) + edgeCost(edge);
                if (newG < gScore.getOrDefault(neighbor.getId(), Double.MAX_VALUE)) {
                    cameFrom.put(neighbor.getId(), edge);
                    gScore.put(neighbor.getId(), newG);
                    openSet.add(new PathNode(neighbor, newG, newG + heuristic(neighbor, end, maxSpeed)));
                }
            }
        }
        return Collections.emptyList();
    }

    private List<Edge> reconstructEdgeList(Map<Long, Edge> cameFrom, Node current) {
        LinkedList<Edge> path = new LinkedList<>();
        while (cameFrom.containsKey(current.getId())) {
            Edge edge = cameFrom.get(current.getId());
            path.addFirst(edge);
            current = edge.getSrcNode();
        }
        return path;
    }

    /**
     * 특정 좌표(x, y)에서 가장 가까운 노드를 찾습니다.
     */
    /**
     * 특정 좌표(x, y)에서 가장 가까운 노드를 찾습니다.
     * contextNode가 주어지면, 해당 노드와 그 이웃 노드들 중에서만 검색합니다. (Topological Tracking)
     */
    public Node findNearestNode(double x, double y) {
        return findNearestNode(x, y, null);
    }

    public Node findNearestNode(double x, double y, Node contextNode) {
        List<Node> candidates;

        if (contextNode == null) {
            candidates = mapDBAdaptor.findAllNodes();
        } else {
            // ContextNode + Neighbors
            candidates = new ArrayList<>();
            candidates.add(contextNode);

            List<Edge> edges = graphCache.getEdges(contextNode);
            if (edges != null) {
                for (Edge edge : edges) {
                    candidates.add(edge.getDstNode());
                }
            }
        }

        return candidates.stream()
                .min(Comparator.comparingDouble(n -> Math.pow(n.getPosX() - x, 2) + Math.pow(n.getPosY() - y, 2)))
                .orElse(null);
    }

    /**
     * HTTP API 요청에 따라 전체 지도 데이터(노드, 간선)를 반환합니다.
     * 
     * @param mapId 지도를 식별하는 ID
     * @return 지도의 전체 구성을 담은 MapResponse DTO
     */
    public MapResponse getFullMap(String mapId) {
        List<Node> dbNodes = mapDBAdaptor.findAllNodes();
        List<Edge> dbEdges = mapDBAdaptor.findAllEdges();
        MapInfo info = mapInfoRepository.findByBasicMapTrue().orElse(null);

        return MapResponse.builder()
                .mapId(mapId)
                .width(info != null ? info.getWidth() : null)
                .height(info != null ? info.getHeight() : null)
                .resolution(info != null ? info.getResolution() : null)
                .originX(info != null ? info.getOriginX() : null)
                .originY(info != null ? info.getOriginY() : null)
                .imagePath(info != null ? info.getImagePath() : null)
                .maxSpeed(info != null ? info.getMaxSpeed() : null)
                .nodes(dbNodes.stream().map(n -> MapResponse.NodeDto.builder()
                        .id(n.getNodeCode())
                        .x(n.getPosX())
                        .y(n.getPosY())
                        .status(n.getStatus().name())
                        .type(n.getNodeType().name())
                        .build()).toList())
                .edges(dbEdges.stream().map(e -> {
                    List<MapResponse.PointDto> waypoints = new java.util.ArrayList<>();
                    if (e.getWaypoints() != null && !e.getWaypoints().isEmpty()) {
                        try {
                            waypoints = objectMapper.readValue(e.getWaypoints(),
                                    new TypeReference<List<MapResponse.PointDto>>() {
                                    });
                        } catch (Exception ex) {
                            // ignore or log
                        }
                    }
                    return MapResponse.EdgeDto.builder()
                            .id(e.getEdgeCode())
                            .from(e.getSrcNode().getNodeCode())
                            .to(e.getDstNode().getNodeCode())
                            .cost(e.getDistance())
                            .waypoints(waypoints)
                            .build();
                }).toList())
                .build();
    }

    // Heuristic: Estimated Travel Time using the max-speed snapshot for this A* search.
    private double heuristic(Node a, Node b, double maxSpeed) {
        double dx = a.getPosX() - b.getPosX();
        double dy = a.getPosY() - b.getPosY();
        double distance = Math.sqrt(dx * dx + dy * dy);

        // MapInfo에서 최대 속도 가져오기 (기본값: 10.0)
        return distance / maxSpeed;
    }

    public double calculateDistance(Node n1, Node n2) {
        if (n1 == null || n2 == null)
            return Double.MAX_VALUE;
        return calculateDistance(n1, n2.getPosX(), n2.getPosY());
    }

    public double calculateDistance(Node n1, double x, double y) {
        if (n1 == null)
            return Double.MAX_VALUE;
        double dx = n1.getPosX() - x;
        double dy = n1.getPosY() - y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
