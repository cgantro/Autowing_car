package com.project.global.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.domain.map.entity.Edge;
import com.project.domain.map.entity.Node;
import com.project.domain.map.entity.NodeType;
import com.project.domain.map.repository.EdgeRepository;
import com.project.domain.map.repository.NodeRepository;
import com.project.global.util.RdpSimplifier;
import com.project.domain.common.MapStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class MapLoadingService {

    private static final double RDP_EPSILON = 0.1;

    private final EdgeRepository edgeRepository;
    private final NodeRepository nodeRepository;
    private final ObjectMapper objectMapper;

    // Regex to match "node_{id}.json"
    private static final Pattern NODE_PATTERN = Pattern.compile("node_([a-zA-Z0-9]+)\\.json");
    // Regex to match "edge_{start}_{end}.json"
    private static final Pattern EDGE_PATTERN = Pattern.compile("edge_([a-zA-Z0-9]+)_to_([a-zA-Z0-9]+).*\\.json");

    @Transactional
    public void loadAll(List<Resource> resources) {
        log.info("Starting batch load for {} map files...", resources.size());

        List<Resource> nodeFiles = new ArrayList<>();
        List<Resource> edgeFiles = new ArrayList<>();

        for (Resource r : resources) {
            String fname = r.getFilename();
            if (fname == null)
                continue;

            if (NODE_PATTERN.matcher(fname).matches()) {
                nodeFiles.add(r);
            } else if (EDGE_PATTERN.matcher(fname).matches()) {
                edgeFiles.add(r);
            } else {
                log.debug("Skipping unrecognized file: {}", fname);
            }
        }

        // 1. Load Nodes first
        log.info("Loading {} node files...", nodeFiles.size());
        for (Resource r : nodeFiles) {
            processNodeResource(r);
        }

        // 2. Load Edges
        log.info("Loading {} edge files...", edgeFiles.size());
        for (Resource r : edgeFiles) {
            processEdgeResource(r);
        }

        log.info("Successfully loaded all map data.");
    }

    private void processNodeResource(Resource resource) {
        try {
            Matcher matcher = NODE_PATTERN.matcher(resource.getFilename());
            if (!matcher.matches())
                return;

            String nodeId = matcher.group(1);

            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode nodesArray = root.get("nodes");

                if (nodesArray != null && nodesArray.isArray()) {
                    for (JsonNode nodeData : nodesArray) {
                        String id = nodeData.get("id").asText();
                        double x = nodeData.get("x").asDouble();
                        double y = nodeData.get("y").asDouble();
                        // yaw matches existing field in json if present, but Node entity usually stores
                        // just pos
                        String typeStr = nodeData.has("type") ? nodeData.get("type").asText() : "NODE";

                        NodeType type = NodeType.NODE;
                        try {
                            type = NodeType.valueOf(typeStr);
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid NodeType '{}' for node {}, defaulting to NODE", typeStr, id);
                        }

                        saveOrUpdateNode(id, x, y, type);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load node resource: " + resource.getDescription(), e);
        }
    }

    private void saveOrUpdateNode(String code, double x, double y, NodeType type) {
        nodeRepository.findByNodeCode(code).ifPresentOrElse(node -> {
            node.setPosX(x);
            node.setPosY(y);
            node.setNodeType(type);
        }, () -> {
            Node newNode = Node.builder()
                    .nodeCode(code)
                    .posX(x)
                    .posY(y)
                    .nodeType(type)
                    .status(MapStatus.AVAILABLE)
                    .restrictionInfo("NONE")
                    .build();
            nodeRepository.save(newNode);
        });
    }

    private void processEdgeResource(Resource resource) {
        try {
            Matcher matcher = EDGE_PATTERN.matcher(resource.getFilename());
            if (!matcher.matches())
                return;

            String startNodeCode = matcher.group(1);
            String endNodeCode = matcher.group(2);

            Node srcNode = nodeRepository.findByNodeCode(startNodeCode)
                    .orElseThrow(() -> new IllegalStateException("Node not found: " + startNodeCode));
            Node dstNode = nodeRepository.findByNodeCode(endNodeCode)
                    .orElseThrow(() -> new IllegalStateException("Node not found: " + endNodeCode));

            try (InputStream is = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(is);
                JsonNode xArray = root.get("x");
                JsonNode yArray = root.get("y");

                List<RdpSimplifier.Point> rawPoints = new ArrayList<>();
                for (int i = 0; i < xArray.size(); i++) {
                    rawPoints.add(new RdpSimplifier.Point(xArray.get(i).asDouble(), yArray.get(i).asDouble()));
                }

                double totalDistance = 0.0;
                for (int i = 0; i < rawPoints.size() - 1; i++) {
                    totalDistance += Math.sqrt(Math.pow(rawPoints.get(i + 1).x - rawPoints.get(i).x, 2) +
                            Math.pow(rawPoints.get(i + 1).y - rawPoints.get(i).y, 2));
                }

                int defaultMaxSpeed = 3;
                double travelTime = totalDistance / defaultMaxSpeed;

                List<RdpSimplifier.Point> simplified = RdpSimplifier.simplify(rawPoints, RDP_EPSILON);
                String waypointsJson = objectMapper.writeValueAsString(simplified);

                // Forward Edge
                upsertEdge("E_" + startNodeCode + "_to_" + endNodeCode,
                        srcNode, dstNode, totalDistance, defaultMaxSpeed, travelTime, waypointsJson);

                // Reverse Edge
                List<RdpSimplifier.Point> reversed = new ArrayList<>(simplified);
                Collections.reverse(reversed);
                String reversedWaypointsJson = objectMapper.writeValueAsString(reversed);

                upsertEdge("E_" + endNodeCode + "_to_" + startNodeCode,
                        dstNode, srcNode, totalDistance, defaultMaxSpeed, travelTime, reversedWaypointsJson);

                log.info("Loaded edge {} -> {}", startNodeCode, endNodeCode);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load map resource: " + resource.getDescription(), e);
        }
    }

    private void upsertEdge(String edgeCode, Node src, Node dst, Double distance, Integer maxSpeed, Double travelTime,
            String waypoints) {
        edgeRepository.findByEdgeCode(edgeCode).ifPresentOrElse(
                edge -> {
                    edge.updatePath(waypoints, distance, travelTime);
                },
                () -> {
                    Edge newEdge = Edge.builder()
                            .edgeCode(edgeCode)
                            .srcNode(src)
                            .dstNode(dst)
                            .distance(distance)
                            .maxSpeed(maxSpeed)
                            .travelTime(travelTime)
                            .waypoints(waypoints)
                            .status(MapStatus.AVAILABLE)
                            .restrictionInfo("NONE")
                            .build();
                    edgeRepository.save(newEdge);
                });
    }
}
