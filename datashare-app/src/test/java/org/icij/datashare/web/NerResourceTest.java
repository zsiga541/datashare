package org.icij.datashare.web;

import net.codestory.http.convert.TypeConvert;
import net.codestory.rest.Response;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpStage;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class NerResourceTest extends AbstractProdWebServerTest {
    @Mock Pipeline pipeline;
    @Mock PipelineRegistry registry;

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        doReturn(true).when(pipeline).initialize(any());
        doReturn(pipeline).when(registry).get(any());
        NerResource nerResource = new NerResource(registry, l -> ENGLISH);
        configure(routes -> routes.add(nerResource));
    }

    @Test
    public void test_post_empty_text() throws Exception {
        Document doc = DocumentBuilder.createDoc("inline").with("").with(ENGLISH).build();
        doReturn(emptyList()).when(pipeline).process(eq(doc));
        post("/api/ner/findNames/CORENLP", doc.getContent()).should().respond(200).contain("[]");

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process(doc);
    }

    @Test
    public void test_get_pipeline_list() throws Exception {
        doReturn(asSet(Pipeline.Type.EMAIL, Pipeline.Type.IXAPIPE)).when(registry).getPipelineTypes();
        get("/api/ner/pipelines").should().respond(200).contain("EMAIL").contain("IXAPIPE");
    }

    @Test
    public void test_post_text_returns_NamedEntity_list() throws Exception {
        Document doc = DocumentBuilder.createDoc("inline").with("This the 'foù' file content.").with(ENGLISH).build();
        final Annotations annotations = new Annotations("inline", CORENLP, ENGLISH);
        annotations.add(NlpStage.NER, 10, 13, NamedEntity.Category.PERSON);
        doReturn(asList(NamedEntity.create(NamedEntity.Category.PERSON, "foù", asList(10L), doc.getId(), "root", CORENLP, ENGLISH))).when(pipeline).process(eq(doc));

        Response response = post("/api/ner/findNames/CORENLP", doc.getContent()).response();

        List actualNerList = TypeConvert.fromJson(response.content(), List.class);
        assertThat(actualNerList).hasSize(1);
        assertThat(actualNerList.get(0)).isInstanceOf(HashMap.class);
        assertThat((Map) actualNerList.get(0)).includes(
                entry("mention", "foù"),
                entry("extractor", "CORENLP"),
                entry("mentionNorm", "fou"),
                entry("offsets", asList(10))
        );
    }
}
