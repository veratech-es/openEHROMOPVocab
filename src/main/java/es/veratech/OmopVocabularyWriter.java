package es.veratech;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Writes OMOP vocabulary data as both SQL INSERT statements and tab-separated CSV files. */
public class OmopVocabularyWriter {

    private static final String SEP = "\t";

    // --- CONCEPT ---

    public void writeConceptSql(Path path, List<OmopModel.OmopConcept> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("-- OMOP CONCEPT rows generated from openEHR archetypes");
            w.newLine();
            for (var r : rows) {
                w.write(formatConceptInsert(r));
                w.newLine();
            }
        }
    }

    public void writeConceptCsv(Path path, List<OmopModel.OmopConcept> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(SEP, "concept_id", "concept_name", "domain_id", "vocabulary_id",
                    "concept_class_id", "standard_concept", "concept_code",
                    "valid_start_date", "valid_end_date", "invalid_reason"));
            w.newLine();
            for (var r : rows) {
                w.write(r.conceptId + SEP + csvEscape(r.conceptName) + SEP + r.domainId + SEP
                        + r.vocabularyId + SEP + r.conceptClassId + SEP + r.standardConcept + SEP
                        + r.conceptCode + SEP + r.validStartDate + SEP + r.validEndDate + SEP
                        + nvl(r.invalidReason));
                w.newLine();
            }
        }
    }

    // --- VOCABULARY ---

    public void writeVocabularySql(Path path, List<OmopModel.OmopVocabulary> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("-- OMOP VOCABULARY rows for openEHR");
            w.newLine();
            for (var r : rows) {
                w.write(String.format(
                    "INSERT INTO VOCABULARY (vocabulary_id, vocabulary_name, vocabulary_reference, vocabulary_version, vocabulary_concept_id) " +
                    "VALUES ('%s', '%s', %s, %s, %d);",
                    r.vocabularyId, esc(r.vocabularyName),
                    sqlNull(r.vocabularyReference), sqlNull(r.vocabularyVersion),
                    r.vocabularyConceptId));
                w.newLine();
            }
        }
    }

    public void writeVocabularyCsv(Path path, List<OmopModel.OmopVocabulary> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(SEP, "vocabulary_id", "vocabulary_name", "vocabulary_reference",
                    "vocabulary_version", "vocabulary_concept_id"));
            w.newLine();
            for (var r : rows) {
                w.write(r.vocabularyId + SEP + csvEscape(r.vocabularyName) + SEP
                        + nvl(r.vocabularyReference) + SEP + nvl(r.vocabularyVersion) + SEP
                        + r.vocabularyConceptId);
                w.newLine();
            }
        }
    }

    // --- CONCEPT_CLASS ---

    public void writeConceptClassSql(Path path, List<OmopModel.OmopConceptClass> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("-- OMOP CONCEPT_CLASS rows for openEHR RM types");
            w.newLine();
            for (var r : rows) {
                w.write(String.format(
                    "INSERT INTO CONCEPT_CLASS (concept_class_id, concept_class_name, concept_class_concept_id) " +
                    "VALUES ('%s', '%s', %d);",
                    r.conceptClassId, esc(r.conceptClassName), r.conceptClassConceptId));
                w.newLine();
            }
        }
    }

    public void writeConceptClassCsv(Path path, List<OmopModel.OmopConceptClass> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(SEP, "concept_class_id", "concept_class_name", "concept_class_concept_id"));
            w.newLine();
            for (var r : rows) {
                w.write(r.conceptClassId + SEP + csvEscape(r.conceptClassName) + SEP + r.conceptClassConceptId);
                w.newLine();
            }
        }
    }

    // --- CONCEPT_RELATIONSHIP ---

    public void writeConceptRelationshipSql(Path path, List<OmopModel.OmopConceptRelationship> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("-- OMOP CONCEPT_RELATIONSHIP rows from OMOCL mappings");
            w.newLine();
            for (var r : rows) {
                w.write(String.format(
                    "INSERT INTO CONCEPT_RELATIONSHIP (concept_id_1, concept_id_2, relationship_id, valid_start_date, valid_end_date, invalid_reason) " +
                    "VALUES (%d, %d, '%s', '%s', '%s', %s);",
                    r.conceptId1, r.conceptId2, r.relationshipId,
                    r.validStartDate, r.validEndDate, sqlNull(r.invalidReason)));
                w.newLine();
            }
        }
    }

    public void writeConceptRelationshipCsv(Path path, List<OmopModel.OmopConceptRelationship> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(SEP, "concept_id_1", "concept_id_2", "relationship_id",
                    "valid_start_date", "valid_end_date", "invalid_reason"));
            w.newLine();
            for (var r : rows) {
                w.write(r.conceptId1 + SEP + r.conceptId2 + SEP + r.relationshipId + SEP
                        + r.validStartDate + SEP + r.validEndDate + SEP + nvl(r.invalidReason));
                w.newLine();
            }
        }
    }

    // --- CONCEPT_SYNONYM ---

    public void writeConceptSynonymSql(Path path, List<OmopModel.OmopConceptSynonym> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("-- OMOP CONCEPT_SYNONYM rows from non-English archetype terms");
            w.newLine();
            for (var r : rows) {
                w.write(String.format(
                    "INSERT INTO CONCEPT_SYNONYM (concept_id, concept_synonym_name, language_concept_id) " +
                    "VALUES (%d, '%s', %d);",
                    r.conceptId, esc(r.conceptSynonymName), r.languageConceptId));
                w.newLine();
            }
        }
    }

    public void writeConceptSynonymCsv(Path path, List<OmopModel.OmopConceptSynonym> rows) throws IOException {
        ensureDir(path);
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(SEP, "concept_id", "concept_synonym_name", "language_concept_id"));
            w.newLine();
            for (var r : rows) {
                w.write(r.conceptId + SEP + csvEscape(r.conceptSynonymName) + SEP + r.languageConceptId);
                w.newLine();
            }
        }
    }

    // --- helpers ---

    private static String formatConceptInsert(OmopModel.OmopConcept r) {
        return String.format(
            "INSERT INTO CONCEPT (concept_id, concept_name, domain_id, vocabulary_id, concept_class_id, standard_concept, concept_code, valid_start_date, valid_end_date, invalid_reason) " +
            "VALUES (%d, '%s', '%s', '%s', '%s', '%s', '%s', '%s', '%s', %s);",
            r.conceptId, esc(r.conceptName), r.domainId, r.vocabularyId,
            r.conceptClassId, r.standardConcept, r.conceptCode,
            r.validStartDate, r.validEndDate, sqlNull(r.invalidReason));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains("\t") || s.contains("\n") || s.contains("\"")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String sqlNull(String s) {
        return s == null ? "NULL" : "'" + esc(s) + "'";
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static void ensureDir(Path filePath) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
