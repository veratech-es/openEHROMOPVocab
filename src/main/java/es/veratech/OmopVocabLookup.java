package es.veratech;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Loads OMOP standard vocabulary CSVs for reference lookups.
 * Reads CONCEPT.csv to build lookup maps for domain_id, language concepts, etc.
 */
public class OmopVocabLookup {

    /** concept_id -> domain_id from CONCEPT.csv */
    private final Map<Long, String> domainByConceptId = new HashMap<>();
    /** concept_id -> concept_name */
    private final Map<Long, String> nameByConceptId = new HashMap<>();
    /** language tag (en, es, ...) -> language concept_id */
    private final Map<String, Long> langConceptIds = new HashMap<>();

    public OmopVocabLookup(Path vocabDir) throws IOException {
        loadConcepts(vocabDir.resolve("CONCEPT.csv"));
    }

    private void loadConcepts(Path conceptCsv) throws IOException {
        if (!Files.isRegularFile(conceptCsv)) {
            System.err.println("WARNING: CONCEPT.csv not found at " + conceptCsv + " — lookups will be empty");
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(conceptCsv, StandardCharsets.UTF_8)) {
            String header = r.readLine(); // skip header
            String line;
            while ((line = r.readLine()) != null) {
                String[] cols = line.split("\t", -1);
                if (cols.length < 9) continue;
                try {
                    long cid = Long.parseLong(cols[0]);
                    String name = cols[1];
                    String domain = cols[2];
                    String vocab = cols[3];
                    String cls = cols[4];

                    domainByConceptId.put(cid, domain);
                    nameByConceptId.put(cid, name);

                    // Detect language concepts: SNOMED Qualifier Value with "language" in name
                    if (cls.equals("Qualifier Value") && vocab.equals("SNOMED")
                            && name.toLowerCase().endsWith(" language")) {
                        String langTag = name.substring(0, name.length() - " language".length()).trim().toLowerCase();
                        // map common language names to ISO codes
                        langConceptIds.put(langTag, cid);
                        // also store as lowercase ISO code if it matches
                        switch (langTag) {
                            case "english": langConceptIds.put("en", cid); break;
                            case "spanish": langConceptIds.put("es", cid); break;
                            case "french":  langConceptIds.put("fr", cid); break;
                            case "german":  langConceptIds.put("de", cid); break;
                            case "portuguese": langConceptIds.put("pt", cid); break;
                            case "dutch":   langConceptIds.put("nl", cid); break;
                            case "italian": langConceptIds.put("it", cid); break;
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    /** Get domain_id for an OMOP standard concept. */
    public String getDomain(long conceptId) {
        return domainByConceptId.getOrDefault(conceptId, "Observation");
    }

    /** Get concept_name for an OMOP standard concept. */
    public String getConceptName(long conceptId) {
        return nameByConceptId.getOrDefault(conceptId, "");
    }

    /** Get the OMOP language concept_id for a given language tag (e.g., "en", "es", "english"). */
    public long getLanguageConceptId(String languageTag) {
        if (languageTag == null) return 4180186L; // default English
        String key = languageTag.toLowerCase();
        // Try exact match first, then fall back
        Long id = langConceptIds.get(key);
        if (id != null) return id;
        // try just the first part before hyphen (e.g., "en-US" -> "en")
        int dash = key.indexOf('-');
        if (dash > 0) {
            id = langConceptIds.get(key.substring(0, dash));
            if (id != null) return id;
        }
        return 4180186L; // default English
    }
}
