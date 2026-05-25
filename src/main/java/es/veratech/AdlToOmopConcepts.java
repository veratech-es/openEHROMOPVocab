package es.veratech;

import com.nedap.archie.adl14.ADL14Parser;
import com.nedap.archie.rminfo.ArchieRMInfoLookup;
import com.nedap.archie.rminfo.MetaModels;
import com.nedap.archie.rminfo.ReferenceModels;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates OMOP vocabulary SQL INSERTs and CSV files from openEHR ADL 1.4 archetypes
 * and OMOCL mappings.
 *
 * <p>Output covers the OMOP tables: CONCEPT, VOCABULARY, CONCEPT_CLASS,
 * CONCEPT_RELATIONSHIP, and CONCEPT_SYNONYM.</p>
 *
 * <p>Usage:<br>
 * {@code java AdlToOmopConcepts <adl_dir> <omocl_dir> <omop_vocab_dir> <output_dir>}</p>
 *
 * <ul>
 *   <li>{@code adl_dir} — local clone of CKM-mirror/local/archetypes</li>
 *   <li>{@code omocl_dir} — local clone of OMOCL/medical_data</li>
 *   <li>{@code omop_vocab_dir} — directory with OMOP vocabulary CSVs (CONCEPT.csv etc.)</li>
 *   <li>{@code output_dir} — where .sql and .csv files are written</li>
 * </ul>
 */
public class AdlToOmopConcepts {

    private static final String VOCABULARY_ID = "openEHR";
    private static final String VOCABULARY_NAME = "openEHR Archetype Terms";
    private static final String VOCABULARY_REF = "https://github.com/openEHR/CKM-mirror";

    private static final long START_ID = 2_000_000_001L;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_DATE;

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: java AdlToOmopConcepts <adl_dir> <omocl_dir> <omop_vocab_dir> <output_dir>");
            System.exit(1);
        }
        Path adlDir = Paths.get(args[0]);
        Path omoclDir = Paths.get(args[1]);
        Path vocabDir = Paths.get(args[2]);
        Path outDir = Paths.get(args[3]);

        new AdlToOmopConcepts().run(adlDir, omoclDir, vocabDir, outDir);
    }

    private void run(Path adlDir, Path omoclDir, Path vocabDir, Path outDir) throws Exception {
        System.out.println("=== openEHROMOPVocab ===");

        // --- Init ---
        ReferenceModels models = new ReferenceModels(ArchieRMInfoLookup.getInstance());
        MetaModels metaModels = new MetaModels(models, null);
        ADL14Parser adl14Parser = new ADL14Parser(metaModels);

        AdlParser adlParser = new AdlParser(adl14Parser);
        OmoclParser omoclParser = new OmoclParser();
        OmopVocabLookup lookup = new OmopVocabLookup(vocabDir);
        OmopVocabularyWriter writer = new OmopVocabularyWriter();

        // --- Phase 1: Parse ADL files ---
        System.out.println("Parsing ADL files from " + adlDir + " ...");
        List<OmopModel.ArchetypeInfo> archetypes = new ArrayList<>();
        try (var paths = Files.walk(adlDir)) {
            paths.filter(p -> Files.isRegularFile(p)
                            && p.toString().toLowerCase().endsWith(".adl"))
                 .forEach(f -> {
                     try {
                         archetypes.add(adlParser.parse(f));
                     } catch (Exception e) {
                         System.err.println("ERROR: " + f + " — " + e.getMessage());
                     }
                 });
        }
        System.out.println("  Parsed " + archetypes.size() + " archetypes.");

        // --- Phase 2: Build CONCEPT rows ---
        System.out.println("Building CONCEPT rows ...");

        long nextId = START_ID;

        // 2a. Vocabulary concept (one for the 'openEHR' vocabulary itself)
        List<OmopModel.OmopConcept> allConcepts = new ArrayList<>();
        long vocabConceptId = nextId++;
        allConcepts.add(new OmopModel.OmopConcept(
            vocabConceptId, VOCABULARY_NAME + " Vocabulary", "Metadata",
            "Vocabulary", "Vocabulary", "S",
            "OMOP generated", "1970-01-01", "2099-12-31", null));

        // 2b. Concept class concepts (one per unique RM type)
        Set<String> rmTypes = new LinkedHashSet<>();
        for (var a : archetypes) {
            rmTypes.add(a.rmType);
            rmTypes.addAll(a.nodeTypes.values());
        }
        rmTypes.remove("CODED_VALUE"); // not a real RM type, it's our synthetic marker
        rmTypes.removeIf(t -> !isValidOpenEhrType(t));

        Map<String, Long> classConceptIds = new LinkedHashMap<>();
        for (String rmType : rmTypes) {
            String clsName = "openEHR " + rmType.substring(0, 1).toUpperCase()
                    + rmType.substring(1).toLowerCase().replace('_', ' ');
            long cid = nextId++;
            classConceptIds.put(rmType, cid);
            allConcepts.add(new OmopModel.OmopConcept(
                cid, clsName, "Metadata", "Concept Class", "Concept Class", "S",
                rmType, "1970-01-01", "2099-12-31", null));
        }

        // 2c. Archetype term concepts
        Map<String, Long> sourceConceptIdMap = new LinkedHashMap<>(); // "archetypeId/atCode" -> concept_id
        String defaultDate = LocalDate.now().format(DATE_FMT);

        for (var a : archetypes) {
            String pubDate = a.publishDate != null
                    ? a.publishDate.format(DATE_FMT)
                    : defaultDate;

            for (var termEntry : a.enTerms.entrySet()) {
                String atCode = termEntry.getKey();
                String name = termEntry.getValue();
                String domain = a.nodeDomains.getOrDefault(atCode, "Measurement");
                String cls = a.nodeTypes.getOrDefault(atCode, a.rmType);

                long cid = nextId++;
                String conceptCode = a.archetypeId + "/" + atCode;

                allConcepts.add(new OmopModel.OmopConcept(
                    cid, truncate(name, 255), domain, VOCABULARY_ID,
                    truncate(cls, 20), "N", truncate(conceptCode, 50),
                    pubDate, "2099-12-31", null));

                sourceConceptIdMap.put(a.archetypeId + "\t" + atCode, cid);
            }
        }

        System.out.println("  Generated " + allConcepts.size() + " CONCEPT rows.");

        // --- Phase 3: VOCABULARY rows ---
        List<OmopModel.OmopVocabulary> vocabRows = List.of(
            new OmopModel.OmopVocabulary(VOCABULARY_ID, VOCABULARY_NAME,
                    VOCABULARY_REF, null, vocabConceptId)
        );

        // --- Phase 4: CONCEPT_CLASS rows ---
        List<OmopModel.OmopConceptClass> classRows = new ArrayList<>();
        for (var entry : classConceptIds.entrySet()) {
            String rmType = entry.getKey();
            long cid = entry.getValue();
            String clsName = "openEHR " + rmType.substring(0, 1).toUpperCase()
                    + rmType.substring(1).toLowerCase().replace('_', ' ');
            classRows.add(new OmopModel.OmopConceptClass(
                    truncate(rmType, 20), truncate(clsName, 255), cid));
        }

        // --- Phase 5: CONCEPT_SYNONYM rows ---
        List<OmopModel.OmopConceptSynonym> synRows = new ArrayList<>();
        for (var a : archetypes) {
            for (var langEntry : a.altLangTerms.entrySet()) {
                String lang = langEntry.getKey();
                long langConceptId = lookup.getLanguageConceptId(lang);

                for (var termEntry : langEntry.getValue().entrySet()) {
                    String atCode = termEntry.getKey();
                    String text = termEntry.getValue();
                    Long sourceId = sourceConceptIdMap.get(a.archetypeId + "\t" + atCode);
                    if (sourceId != null && text != null && !text.isEmpty()) {
                        synRows.add(new OmopModel.OmopConceptSynonym(
                                sourceId, truncate(text, 1000), langConceptId));
                    }
                }
            }
        }
        System.out.println("  Generated " + synRows.size() + " CONCEPT_SYNONYM rows.");

        // --- Phase 6: Parse OMOCL and build CONCEPT_RELATIONSHIP rows ---
        System.out.println("Parsing OMOCL mappings from " + omoclDir + " ...");
        List<OmopModel.OmopConceptRelationship> relRows = new ArrayList<>();

        if (Files.isDirectory(omoclDir)) {
            List<OmopModel.OmoclMapping> omoclMappings = omoclParser.parseDirectory(omoclDir);
            System.out.println("  Parsed " + omoclMappings.size() + " OMOCL mappings.");

            for (var mapping : omoclMappings) {
                String lookupKey = mapping.archetypeId + "\t" + mapping.sourceAtCode;
                Long sourceConceptId = sourceConceptIdMap.get(lookupKey);

                if (sourceConceptId == null) {
                    sourceConceptId = fuzzyMatch(sourceConceptIdMap, mapping.archetypeId,
                                                  mapping.sourceAtCode);
                }

                if (sourceConceptId != null) {
                    relRows.add(new OmopModel.OmopConceptRelationship(
                        sourceConceptId, mapping.targetOmopConceptId, "Maps to",
                        defaultDate, "2099-12-31", null));
                } else if (System.getenv("DEBUG") != null) {
                    System.err.println("DEBUG: no source concept for " + mapping.archetypeId
                            + " / " + mapping.sourceAtCode);
                }
            }
        } else {
            System.out.println("  OMOCL dir not found, skipping CONCEPT_RELATIONSHIP generation.");
        }
        System.out.println("  Generated " + relRows.size() + " CONCEPT_RELATIONSHIP rows.");

        // --- Phase 7: Write output ---
        System.out.println("Writing output to " + outDir + " ...");
        Files.createDirectories(outDir);

        // CONCEPT
        writer.writeConceptSql(outDir.resolve("concept.sql"), allConcepts);
        writer.writeConceptCsv(outDir.resolve("concept.csv"), allConcepts);

        // VOCABULARY
        writer.writeVocabularySql(outDir.resolve("vocabulary.sql"), vocabRows);
        writer.writeVocabularyCsv(outDir.resolve("vocabulary.csv"), vocabRows);

        // CONCEPT_CLASS
        writer.writeConceptClassSql(outDir.resolve("concept_class.sql"), classRows);
        writer.writeConceptClassCsv(outDir.resolve("concept_class.csv"), classRows);

        // CONCEPT_RELATIONSHIP
        writer.writeConceptRelationshipSql(outDir.resolve("concept_relationship.sql"), relRows);
        writer.writeConceptRelationshipCsv(outDir.resolve("concept_relationship.csv"), relRows);

        // CONCEPT_SYNONYM
        writer.writeConceptSynonymSql(outDir.resolve("concept_synonym.sql"), synRows);
        writer.writeConceptSynonymCsv(outDir.resolve("concept_synonym.csv"), synRows);

        System.out.println("Done. Output written to " + outDir.toAbsolutePath());
        System.out.println("  concept.sql / concept.csv");
        System.out.println("  vocabulary.sql / vocabulary.csv");
        System.out.println("  concept_class.sql / concept_class.csv");
        System.out.println("  concept_relationship.sql / concept_relationship.csv");
        System.out.println("  concept_synonym.sql / concept_synonym.csv");
    }

    /**
     * Fuzzy-match an OMOCL mapping to an ADL-parsed concept.
     * Handles case differences, version mismatches, and prefix matching.
     */
    private static Long fuzzyMatch(Map<String, Long> sourceMap, String omoclArchetypeId, String atCode) {
        // 1) Try case-insensitive exact match
        String targetKey = omoclArchetypeId + "\t" + atCode;
        for (var e : sourceMap.entrySet()) {
            if (e.getKey().equalsIgnoreCase(targetKey)) {
                return e.getValue();
            }
        }

        // 2) Try matching without version suffix (height.v1 → height.v2)
        String baseNoVersion = stripVersion(omoclArchetypeId);
        for (var e : sourceMap.entrySet()) {
            String[] parts = e.getKey().split("\t", 2);
            if (parts.length == 2 && parts[1].equalsIgnoreCase(atCode)) {
                String adlId = parts[0];
                if (adlId.equalsIgnoreCase(omoclArchetypeId)
                        || stripVersion(adlId).equalsIgnoreCase(baseNoVersion)) {
                    return e.getValue();
                }
            }
        }

        // 3) Prefix match (OMOCL ID prefixes the ADL ID or vice versa)
        for (var e : sourceMap.entrySet()) {
            String[] parts = e.getKey().split("\t", 2);
            if (parts.length == 2 && parts[1].equalsIgnoreCase(atCode)) {
                String adlId = parts[0].toLowerCase();
                String omoclId = omoclArchetypeId.toLowerCase();
                if (adlId.startsWith(omoclId) || omoclId.startsWith(adlId)) {
                    return e.getValue();
                }
            }
        }

        return null;
    }

    /** Strip the version suffix from an archetype ID. "x.y.v2" → "x.y" */
    private static String stripVersion(String archetypeId) {
        if (archetypeId == null) return null;
        int lastDot = archetypeId.lastIndexOf('.');
        if (lastDot > 0) {
            String afterDot = archetypeId.substring(lastDot + 1);
            if (afterDot.startsWith("v") && afterDot.length() > 1
                    && Character.isDigit(afterDot.charAt(1))) {
                return archetypeId.substring(0, lastDot);
            }
        }
        return archetypeId;
    }

    /** Known openEHR Reference Model types that are valid as concept classes. */
    private static final Set<String> VALID_RM_TYPES = Set.of(
        "COMPOSITION", "SECTION", "ENTRY", "ADMIN_ENTRY",
        "OBSERVATION", "EVALUATION", "INSTRUCTION", "ACTION",
        "CLUSTER", "ELEMENT", "ITEM_TREE", "ITEM_LIST", "ITEM_TABLE", "ITEM_SINGLE",
        "EVENT", "HISTORY", "POINT_EVENT", "INTERVAL_EVENT",
        "DV_CODED_TEXT", "DV_QUANTITY", "DV_COUNT", "DV_PROPORTION", "DV_ORDINAL",
        "DV_BOOLEAN", "DV_TEXT", "DV_DATE", "DV_TIME", "DV_DATE_TIME", "DV_DURATION",
        "DV_URI", "DV_EHR_URI", "DV_PARSABLE", "DV_MULTIMEDIA", "DV_IDENTIFIER",
        "ISM_TRANSITION", "INSTRUCTION_DETAILS", "ACTIVITY",
        "EVENT_CONTEXT", "CAPABILITY",
        "ADDRESS", "CONTACT", "PARTY", "ROLE", "PERSON", "ORGANISATION", "GROUP", "ACTOR", "LINK"
    );

    private static boolean isValidOpenEhrType(String type) {
        return type != null && VALID_RM_TYPES.contains(type);
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }
}
