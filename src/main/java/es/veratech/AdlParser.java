package es.veratech;

import com.nedap.archie.adl14.ADL14ConversionConfiguration;
import com.nedap.archie.adl14.ADL14Parser;
import com.nedap.archie.aom.*;
import com.nedap.archie.aom.terminology.ArchetypeTerm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Parses openEHR ADL 1.4 archetype files and extracts terminology, definition tree,
 * and metadata into {@link OmopModel.ArchetypeInfo}.
 */
public class AdlParser {

    private static final String DEFAULT_LANGUAGE = "en";
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_DATE;

    // RM types whose value implies a value set (coded/ordinal answers)
    private static final Set<String> CODED_VALUE_TYPES = Set.of(
        "DV_CODED_TEXT", "DV_ORDINAL", "DV_BOOLEAN"
    );

    private final ADL14Parser parser;

    public AdlParser(ADL14Parser parser) {
        this.parser = parser;
    }

    public OmopModel.ArchetypeInfo parse(Path adlPath) throws IOException {
        String adlText = Files.readString(adlPath, StandardCharsets.UTF_8);
        if (adlText.startsWith("﻿")) {
            adlText = adlText.substring(1);
        }

        Archetype archetype;
        try {
            archetype = parser.parse(adlText, new ADL14ConversionConfiguration());
        } catch (Exception e) {
            throw new IOException("Failed to parse ADL: " + adlPath, e);
        }
        if (archetype == null) {
            throw new IOException("Parser returned null for: " + adlPath);
        }

        OmopModel.ArchetypeInfo info = new OmopModel.ArchetypeInfo();

        // --- Archetype ID ---
        info.archetypeId = archetype.getArchetypeId() != null
                ? archetype.getArchetypeId().toString()
                : "UNKNOWN";

        // --- RM type of root ---
        info.rmType = (archetype.getDefinition() != null && archetype.getDefinition().getRmTypeName() != null)
                ? archetype.getDefinition().getRmTypeName()
                : "Element";

        // --- Publish date ---
        info.publishDate = extractPublishDate(archetype);

        // --- Concept name from root term ---
        info.conceptName = extractConceptName(archetype);

        // --- Walk definition tree ---
        Set<String> structuralNodeIds = new LinkedHashSet<>();
        Map<String, String> nodeTypes = new LinkedHashMap<>();
        Map<String, String> nodeDomains = new LinkedHashMap<>();
        if (archetype.getDefinition() != null) {
            walkTree(archetype.getDefinition(), structuralNodeIds, nodeTypes, nodeDomains, false);
        }
        info.nodeTypes = nodeTypes;
        info.nodeDomains = nodeDomains;

        // --- Terminology ---
        if (archetype.getTerminology() != null) {
            Map<String, Map<String, ArchetypeTerm>> termDefs = archetype.getTerminology().getTermDefinitions();

            // English terms (primary)
            Map<String, ArchetypeTerm> enDefs = termDefs.get(DEFAULT_LANGUAGE);
            if (enDefs == null && !termDefs.isEmpty()) {
                enDefs = termDefs.values().iterator().next();
            }
            if (enDefs != null) {
                for (Map.Entry<String, ArchetypeTerm> e : enDefs.entrySet()) {
                    info.enTerms.put(e.getKey(), e.getValue().getText());
                }
            }

            // Other languages (synonyms)
            for (Map.Entry<String, Map<String, ArchetypeTerm>> langEntry : termDefs.entrySet()) {
                String lang = langEntry.getKey();
                if (lang.equals(DEFAULT_LANGUAGE) && enDefs != null) continue;
                // skip if this was the fallback primary
                if (enDefs == termDefs.get(lang)) continue;

                Map<String, String> synTerms = new LinkedHashMap<>();
                for (Map.Entry<String, ArchetypeTerm> te : langEntry.getValue().entrySet()) {
                    synTerms.put(te.getKey(), te.getValue().getText());
                }
                if (!synTerms.isEmpty()) {
                    info.altLangTerms.put(lang, synTerms);
                }
            }
        }

        // --- Classify domain for all term codes ---
        for (String atCode : info.enTerms.keySet()) {
            if (nodeDomains.containsKey(atCode)) {
                continue; // already classified from tree walk
            }
            // Not a structural node → likely a value set code
            nodeDomains.put(atCode, "Meas Value");
            nodeTypes.put(atCode, "CODED_VALUE");
        }

        return info;
    }

    /** Walk the C_OBJECT tree collecting structural node ids, types, and domains. */
    private void walkTree(CObject node, Set<String> nodeIds, Map<String, String> nodeTypes,
                          Map<String, String> nodeDomains, boolean parentIsCodedElement) {

        String nodeId = node.getNodeId();
        String rmType = node.getRmTypeName();

        if (nodeId != null) {
            nodeIds.add(nodeId);
            nodeTypes.put(nodeId, rmType != null ? rmType : "Element");

            if (parentIsCodedElement) {
                nodeDomains.put(nodeId, "Meas Value");
            } else {
                nodeDomains.put(nodeId, domainForRmType(rmType));
            }
        }

        if (node instanceof CComplexObject complex) {
            if (complex.getAttributes() == null) return;

            // Check if this ELEMENT has a coded value type
            boolean isCodedElement = false;
            if ("ELEMENT".equals(rmType)) {
                for (CAttribute attr : complex.getAttributes()) {
                    if ("value".equals(attr.getRmAttributeName()) && attr.getChildren() != null) {
                        for (CObject child : attr.getChildren()) {
                            String childRm = child.getRmTypeName();
                            if (childRm != null && CODED_VALUE_TYPES.contains(childRm)) {
                                isCodedElement = true;
                                // Mark this ELEMENT as Meas Value
                                if (nodeId != null) {
                                    nodeDomains.put(nodeId, "Meas Value");
                                }
                                break;
                            }
                        }
                    }
                }
            }

            boolean childIsCoded = parentIsCodedElement || isCodedElement;

            for (CAttribute attr : complex.getAttributes()) {
                if (attr.getChildren() != null) {
                    for (CObject child : attr.getChildren()) {
                        walkTree(child, nodeIds, nodeTypes, nodeDomains, childIsCoded);
                    }
                }
            }
        }
    }

    private static String domainForRmType(String rmType) {
        if (rmType == null) return "Measurement";
        return switch (rmType) {
            case "ELEMENT" -> "Measurement";   // overridden by child check in walkTree
            case "OBSERVATION", "EVALUATION", "INSTRUCTION", "ACTION",
                 "CLUSTER", "ITEM_TREE", "HISTORY", "EVENT", "ITEM_LIST",
                 "ITEM_TABLE", "ITEM_SINGLE" -> "Measurement";
            default -> "Measurement";
        };
    }

    private static String extractConceptName(Archetype archetype) {
        try {
            Map<String, ArchetypeTerm> defs = archetype.getTerminology()
                    .getTermDefinitions().get(DEFAULT_LANGUAGE);
            if (defs != null) {
                ArchetypeTerm rootTerm = defs.get("at0000");
                if (rootTerm != null) return rootTerm.getText();
            }
        } catch (Exception ignored) {}
        return archetype.getArchetypeId() != null
                ? archetype.getArchetypeId().getConceptId()
                : "Unknown";
    }

    private static LocalDate extractPublishDate(Archetype archetype) {
        try {
            Object author = archetype.getDescription().getOriginalAuthor();
            if (author instanceof Map<?, ?> map) {
                Object dateVal = map.get("date");
                if (dateVal != null) {
                    String dateStr = dateVal.toString();
                    if (!dateStr.isEmpty()) {
                        return LocalDate.parse(dateStr, ISO_DATE);
                    }
                }
            }
        } catch (Exception ignored) {}
        return LocalDate.now();
    }
}
