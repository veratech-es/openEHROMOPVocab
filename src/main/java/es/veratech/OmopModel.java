package es.veratech;

import java.time.LocalDate;
import java.util.*;

/** Data model for OMOP vocabulary tables. */
public class OmopModel {

    public static class OmopConcept {
        public long conceptId;
        public String conceptName;
        public String domainId;
        public String vocabularyId;
        public String conceptClassId;
        public String standardConcept;
        public String conceptCode;
        public String validStartDate;
        public String validEndDate;
        public String invalidReason;

        public OmopConcept(long conceptId, String conceptName, String domainId, String vocabularyId,
                           String conceptClassId, String standardConcept, String conceptCode,
                           String validStartDate, String validEndDate, String invalidReason) {
            this.conceptId = conceptId;
            this.conceptName = conceptName;
            this.domainId = domainId;
            this.vocabularyId = vocabularyId;
            this.conceptClassId = conceptClassId;
            this.standardConcept = standardConcept;
            this.conceptCode = conceptCode;
            this.validStartDate = validStartDate;
            this.validEndDate = validEndDate;
            this.invalidReason = invalidReason;
        }
    }

    public static class OmopVocabulary {
        public String vocabularyId;
        public String vocabularyName;
        public String vocabularyReference;
        public String vocabularyVersion;
        public long vocabularyConceptId;

        public OmopVocabulary(String vocabularyId, String vocabularyName, String vocabularyReference,
                              String vocabularyVersion, long vocabularyConceptId) {
            this.vocabularyId = vocabularyId;
            this.vocabularyName = vocabularyName;
            this.vocabularyReference = vocabularyReference;
            this.vocabularyVersion = vocabularyVersion;
            this.vocabularyConceptId = vocabularyConceptId;
        }
    }

    public static class OmopConceptClass {
        public String conceptClassId;
        public String conceptClassName;
        public long conceptClassConceptId;

        public OmopConceptClass(String conceptClassId, String conceptClassName, long conceptClassConceptId) {
            this.conceptClassId = conceptClassId;
            this.conceptClassName = conceptClassName;
            this.conceptClassConceptId = conceptClassConceptId;
        }
    }

    public static class OmopConceptRelationship {
        public long conceptId1;
        public long conceptId2;
        public String relationshipId;
        public String validStartDate;
        public String validEndDate;
        public String invalidReason;

        public OmopConceptRelationship(long conceptId1, long conceptId2, String relationshipId,
                                       String validStartDate, String validEndDate, String invalidReason) {
            this.conceptId1 = conceptId1;
            this.conceptId2 = conceptId2;
            this.relationshipId = relationshipId;
            this.validStartDate = validStartDate;
            this.validEndDate = validEndDate;
            this.invalidReason = invalidReason;
        }
    }

    public static class OmopConceptSynonym {
        public long conceptId;
        public String conceptSynonymName;
        public long languageConceptId;

        public OmopConceptSynonym(long conceptId, String conceptSynonymName, long languageConceptId) {
            this.conceptId = conceptId;
            this.conceptSynonymName = conceptSynonymName;
            this.languageConceptId = languageConceptId;
        }
    }

    /** Parsed result from one ADL archetype. */
    public static class ArchetypeInfo {
        public String archetypeId;
        public String conceptName;          // root concept name (en)
        public LocalDate publishDate;
        public String rmType;               // root RM type name
        /** at_code -> english term text. Primary terms used as concept_name. */
        public Map<String, String> enTerms = new LinkedHashMap<>();
        /** language_code -> (at_code -> term text). Secondary languages become synonyms. */
        public Map<String, Map<String, String>> altLangTerms = new LinkedHashMap<>();
        /** at_code -> domain_id, determined by node's constraint type. */
        public Map<String, String> nodeDomains = new LinkedHashMap<>();
        /** at_code -> RM type name for the node. */
        public Map<String, String> nodeTypes = new LinkedHashMap<>();
    }

    /** Parsed mapping from one OMOCL YAML entry. */
    public static class OmoclMapping {
        public String archetypeId;
        public String sourceAtCode;         // at code from the archetype
        public long targetOmopConceptId;    // OMOP concept ID
        public String mappingKind;          // "concept_id" or "value_conceptmap"
    }
}
