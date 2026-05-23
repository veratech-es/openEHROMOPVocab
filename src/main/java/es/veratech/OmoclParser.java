package es.veratech;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Parses OMOCL YAML mapping files to extract relationships between
 * openEHR archetype codes and OMOP standard concept IDs.
 */
public class OmoclParser {

    // Extract at codes from paths like "data[at0001]/events[at0006]/data[at0003]/items[at0004]"
    private static final Pattern AT_CODE_PATTERN = Pattern.compile("\\[(at\\d+)\\]");

    private final Yaml yaml = new Yaml();

    /** Parse all YAML files in a directory tree, returning flat list of mappings. */
    public List<OmopModel.OmoclMapping> parseDirectory(Path omoclDir) throws IOException {
        List<OmopModel.OmoclMapping> all = new ArrayList<>();
        try (var paths = Files.walk(omoclDir)) {
            paths.filter(p -> Files.isRegularFile(p)
                            && (p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")))
                 .forEach(f -> {
                     try {
                         all.addAll(parseFile(f));
                     } catch (Exception e) {
                         System.err.println("WARNING: skipping OMOCL file " + f + ": " + e.getMessage());
                     }
                 });
        }
        return all;
    }

    /** Parse a single OMOCL YAML file. */
    @SuppressWarnings("unchecked")
    public List<OmopModel.OmoclMapping> parseFile(Path yamlFile) throws IOException {
        List<OmopModel.OmoclMapping> result = new ArrayList<>();

        Object parsed;
        try (Reader r = Files.newBufferedReader(yamlFile, StandardCharsets.UTF_8)) {
            parsed = yaml.load(r);
        }
        if (!(parsed instanceof Map)) return result;

        Map<String, Object> root = (Map<String, Object>) parsed;

        // Extract archetype ID from spec.openEhrConfig.archetype
        String archetypeId = extractArchetypeId(root);
        if (archetypeId == null) return result;

        // Process mappings list
        Object mappingsObj = root.get("mappings");
        if (!(mappingsObj instanceof List)) return result;

        List<Object> mappings = (List<Object>) mappingsObj;
        for (Object mObj : mappings) {
            if (!(mObj instanceof Map)) continue;
            Map<String, Object> mapping = (Map<String, Object>) mObj;
            result.addAll(processMapping(archetypeId, mapping));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static String extractArchetypeId(Map<String, Object> root) {
        try {
            Map<String, Object> spec = (Map<String, Object>) root.get("spec");
            if (spec == null) return null;
            Map<String, Object> oec = (Map<String, Object>) spec.get("openEhrConfig");
            if (oec == null) return null;
            return (String) oec.get("archetype");
        } catch (ClassCastException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<OmopModel.OmoclMapping> processMapping(String archetypeId, Map<String, Object> mapping) {
        List<OmopModel.OmoclMapping> result = new ArrayList<>();

        // 1) Extract target concept_id from concept_id.alternatives[].code
        Long targetConceptId = extractConceptId(mapping);
        if (targetConceptId == null) return result;

        // 2) Extract source at_codes from value paths
        Map<String, Object> valueSection = (Map<String, Object>) mapping.get("value");
        if (valueSection == null) return result;

        List<Object> valueAlternatives = (List<Object>) valueSection.get("alternatives");
        if (valueAlternatives == null) return result;

        for (Object altObj : valueAlternatives) {
            if (!(altObj instanceof Map)) continue;
            Map<String, Object> alt = (Map<String, Object>) altObj;

            // Check for conceptMap (value → coded value mappings)
            Map<String, Object> conceptMap = (Map<String, Object>) alt.get("conceptMap");
            if (conceptMap != null) {
                Map<String, Object> mapEntries = (Map<String, Object>) conceptMap.get("mapping");
                if (mapEntries != null) {
                    for (Map.Entry<String, Object> entry : mapEntries.entrySet()) {
                        String atCode = entry.getKey();
                        Object val = entry.getValue();
                        long omopId = toLong(val);
                        if (omopId > 0) {
                            OmopModel.OmoclMapping om = new OmopModel.OmoclMapping();
                            om.archetypeId = archetypeId;
                            om.sourceAtCode = atCode;
                            om.targetOmopConceptId = omopId;
                            om.mappingKind = "value_conceptmap";
                            result.add(om);
                        }
                    }
                }
                continue; // don't fall through to path extraction for conceptMap entries
            }

            // Path-based alternative: extract last at_code from path
            String path = (String) alt.get("path");
            if (path != null) {
                String lastAtCode = extractLastAtCode(path);
                if (lastAtCode != null) {
                    OmopModel.OmoclMapping om = new OmopModel.OmoclMapping();
                    om.archetypeId = archetypeId;
                    om.sourceAtCode = lastAtCode;
                    om.targetOmopConceptId = targetConceptId;
                    om.mappingKind = "concept_id";
                    result.add(om);
                }
            }
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private static Long extractConceptId(Map<String, Object> mapping) {
        try {
            Map<String, Object> cidSection = (Map<String, Object>) mapping.get("concept_id");
            if (cidSection == null) return null;
            List<Object> alts = (List<Object>) cidSection.get("alternatives");
            if (alts == null || alts.isEmpty()) return null;
            Object first = alts.get(0);
            if (first instanceof Map) {
                Object code = ((Map<String, Object>) first).get("code");
                long id = toLong(code);
                return id > 0 ? id : null;
            }
        } catch (ClassCastException ignored) {}
        return null;
    }

    /** Extract the last at code from a path like "data[at0001]/events[at0006]/items[at0004]" */
    private static String extractLastAtCode(String path) {
        Matcher m = AT_CODE_PATTERN.matcher(path);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
