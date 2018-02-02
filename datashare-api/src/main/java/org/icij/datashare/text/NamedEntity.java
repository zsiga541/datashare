package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import me.xuender.unidecode.Unidecode;
import org.icij.datashare.Entity;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.function.ThrowingFunctions;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.Tag;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Math.min;
import static java.util.Arrays.fill;
import static java.util.Collections.emptyList;
import static org.icij.datashare.function.ThrowingFunctions.removePattFrom;
import static org.icij.datashare.text.NamedEntity.Category.UNKNOWN;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.NlpStage.POS;


@IndexType("NamedEntity")
public final class NamedEntity implements Entity {
    private static final long serialVersionUID = 1946532866377498L;

    private String mention;

    @IndexId
    @JsonIgnore
    private String hash;
    private Category category;
    @IndexParent
    private String document;
    private int offset;
    private Pipeline.Type extractor;
    private Language extractorLanguage;
    private String partsOfSpeech;

    public enum Category implements Serializable {
        PERSON       ("PERS"),
        ORGANIZATION ("ORG"),
        LOCATION     ("LOC"),
        DATE         ("DATE"),
        MONEY        ("MON"),
        NUMBER       ("NUM"),
        NONE         ("NONE"),
        UNKNOWN      ("UNK");

        private static final long serialVersionUID = -1596432856473673L;

        private final String abbreviation;

        Category(final String abbrev) { abbreviation = abbrev; }

        public String getAbbreviation() { return abbreviation; }

        public static Optional<Category> parse(String entityCategory) {
            if (entityCategory == null || entityCategory.isEmpty())
                return Optional.empty();
            if (entityCategory.trim().equals("0") || entityCategory.trim().equals("O"))
                return Optional.of(NONE);
            try {
                return Optional.of(valueOf(entityCategory.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                String normEntityCategory = removePattFrom.apply("^I-").apply(entityCategory);
                for (Category cat : values()) {
                    String catAbbreviation = cat.getAbbreviation();
                    if (    normEntityCategory.equalsIgnoreCase(catAbbreviation) ||
                            normEntityCategory.equalsIgnoreCase(catAbbreviation.substring(0, min(catAbbreviation.length(), 3))) )
                        return Optional.of(cat);
                }
                return Optional.empty();
            }
        }

        public static ThrowingFunction<List<String>, List<Category>> parseAll =
                list -> list.stream()
                        .map(Category::parse)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
    }


    /**
     * Instantiate a new {@code NamedEntity} from mere category and mention, without context document
     *
     * @param cat      the named entity category
     * @param mention  the string denoting the named entity
     * @return a new immutable {@link NamedEntity} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<NamedEntity> create(Category cat, String mention)  {
        try {
            return Optional.of( new NamedEntity(mention, cat, "", -1, null, null, null) );
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to create named entity", e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate new {@code NamedEntity}, with context document, without part-of-speech
     *
     * @param cat      the named entity category
     * @param mention  the string denoting the named entity
     * @param offset   the offset in context document
     * @param doc      the context document hash
     * @param extr     the pipeline that extracted mention
     * @param extrLang the pipeline language
     * @return a new immutable {@link NamedEntity} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<NamedEntity> create(Category cat,
                                               String mention,
                                               int offset,
                                               String doc,
                                               Pipeline.Type extr,
                                               Language extrLang) {
        try {
            return Optional.of( new NamedEntity(mention, cat, doc, offset, extr, extrLang, null) );
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to createList named entity", e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate new {@code NamedEntity}, with context document, with part-of-speech
     *
     * @param cat      the named entity category
     * @param mention  the string denoting the named entity
     * @param offset   the offset in context document
     * @param doc      the context document hash
     * @param extr     the pipeline that extracted mention
     * @param extrLang the pipeline language
     * @param pos      the part(s)-of-speech associated to mention
     * @return an Optional of immutable {@link NamedEntity} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<NamedEntity> create(Category cat,
                                               String mention,
                                               int offset,
                                               String doc,
                                               Pipeline.Type extr,
                                               Language extrLang,
                                               String pos) {
        try {
            return Optional.of( new NamedEntity(mention, cat, doc, offset, extr, extrLang, pos) );
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to createList named entity", e);
            return Optional.empty();
        }
    }


    /**
     * Named entities from {@link Document}, {@link Annotations}
     *
     * @param document   the document holding textual content
     * @param annotations the annotations holding named entities coordinates within document
     * @return the list of corresponding named entities if annotations does refer to document; an empty list otherwise
     */
    public static List<NamedEntity> allFrom(Document document, Annotations annotations) {
        if ( ! annotations.getDocumentId().equals(document.getId()))
            return emptyList();
        return annotations.get(NER).stream()
                .map     ( tag -> from(document.getContent(), tag, annotations) )
                .filter  ( Optional::isPresent )
                .map     ( Optional::get )
                .collect ( Collectors.toList() );
    }

    public static List<NamedEntity> allFrom(String text, Annotations annotations) {
        return annotations.get(NER).stream()
                .map     ( tag -> from(text, tag, annotations) )
                .filter  ( Optional::isPresent )
                .map     ( Optional::get )
                .collect ( Collectors.toList() );
    }

    public static Optional<NamedEntity> from(String text, Tag tag, Annotations annotations) {
        Optional<NamedEntity.Category> category = NamedEntity.Category.parse(tag.getValue());
        if ( ! category.isPresent())
            return Optional.empty();
        String mention = ThrowingFunctions.removeNewLines.apply(text.substring(tag.getBegin(), tag.getEnd()));
        List<Tag> posTags = annotations.get(POS);
        int posTagIndex = Collections.binarySearch(posTags, tag, Tag.comparator);
        if (posTagIndex > 0) {
            LOGGER.info(posTagIndex + ", " + posTags.get(posTagIndex));
        }
        return NamedEntity.create(
                category.get(),
                mention,
                tag.getBegin(),
                annotations.getDocumentId(),
                annotations.getPipelineType(),
                annotations.getLanguage()
        );
    }

    private NamedEntity() {}

    @JsonCreator
    private NamedEntity(String           mention,
                        Category         category,
                        String           document,
                        int              offset,
                        Pipeline.Type extractor,
                        Language         extractorLanguage,
                        String           partsOfSpeech) {
        if (mention == null || mention.isEmpty()) {
            throw new IllegalArgumentException("Mention is undefined");
        }
        this.category = Optional.ofNullable(category).orElse(UNKNOWN);
        this.mention = mention;
        this.document = document;
        this.offset = offset;
        this.extractor = extractor;
        this.hash = HASHER.hash( String.join("|",
                getDocument().toString(),
                String.valueOf(offset),
                getExtractor().toString(),
                normalize(mention)
        ));
        this.extractorLanguage = extractorLanguage;
        this.partsOfSpeech = partsOfSpeech;
    }

    @Override
    public String getId() { return hash; }

    public String getMention() { return mention; }

    public Category getCategory() { return category; }

    public Optional<String> getDocument() { return Optional.ofNullable(document); }

    public OptionalInt getOffset() { return OptionalInt.of(offset); }

    public Optional<Pipeline.Type> getExtractor() { return Optional.ofNullable(extractor); }

    public Optional<Language> getExtractorLanguage() { return Optional.ofNullable(extractorLanguage); }

    public Optional<String> getPartsOfSpeech() { return Optional.ofNullable(partsOfSpeech); }

    @Override
    public String toString() {
        String[] features = new String[9];
        fill(features, "NONE");
        features[0] = getMention();
        features[1] = getCategory().toString();
        getOffset().ifPresent(off -> features[2] = String.valueOf(off));
        getExtractor().ifPresent(extr -> features[3] = extr.toString());
        getExtractorLanguage().ifPresent(extrLang -> features[4] = extrLang.toString().toUpperCase(Locale.ROOT));
        getPartsOfSpeech().ifPresent(pos -> features[5] = pos.toUpperCase(Locale.ROOT));
        features[6] = normalize(mention);
        features[7] = getId();
        getDocument().ifPresent(docHash -> features[8] = docHash);
        return String.join(";", Arrays.stream(features)
                .map(f -> '"' + f + '"')
                .collect(Collectors.toList()));
    }

    @JsonIgnore
    public static String normalize(String unicoded) {
        return Unidecode.decode(unicoded).trim().replaceAll("(\\s+)", " ").toLowerCase();
    }

}
