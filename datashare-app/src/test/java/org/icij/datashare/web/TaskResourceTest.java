package org.icij.datashare.web;

import net.codestory.http.filters.Filter;
import net.codestory.http.routes.Routes;
import net.codestory.rest.RestAssert;
import net.codestory.rest.ShouldChain;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.nlp.EmailPipeline;
import org.icij.datashare.nlp.NlpApp;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.tasks.*;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractModels;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.jetbrains.annotations.NotNull;
import org.junit.*;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static java.lang.String.format;
import static java.util.Collections.singleton;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.MapAssert.entry;
import static org.icij.datashare.session.DatashareUser.local;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class TaskResourceTest extends AbstractProdWebServerTest {
    @Rule public DatashareTimeRule time = new DatashareTimeRule("2021-07-07T12:23:34Z");
    private static final TaskFactory taskFactory = mock(TaskFactory.class);
    private static final TaskManagerMemory taskManager= new TaskManagerMemory(new PropertiesProvider());

    @Before
    public void setUp() {
        PipelineRegistry pipelineRegistry = new PipelineRegistry(new PropertiesProvider());
        pipelineRegistry.register(EmailPipeline.class);
        configure(new CommonMode(new Properties()) {
                    @Override
                    protected void configure() {
                        bind(TaskFactory.class).toInstance(taskFactory);
                        bind(Indexer.class).toInstance(mock(Indexer.class));
                        bind(TaskManager.class).toInstance(taskManager);
                        bind(PipelineRegistry.class).toInstance(pipelineRegistry);
                        bind(Filter.class).to(LocalUserFilter.class).asEagerSingleton();
                        bind(PropertiesProvider.class).toInstance(new PropertiesProvider(getDefaultProperties()));
                    }
            @Override protected Routes addModeConfiguration(Routes routes) {
                        return routes.add(TaskResource.class).filter(LocalUserFilter.class);}
                }.createWebConfiguration());
        init(taskFactory);
    }

    @After
    public void tearDown() {
        taskManager.waitTasksToBeDone(1, SECONDS);
        taskManager.clearDoneTasks();
    }

    @Test
    public void test_index_file() {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.name).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_file_and_filter() {
        RestAssert response = post("/api/task/batchUpdate/index/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{\"options\":{\"filter\": true}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.name).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));

        verify(taskFactory).createScanIndexTask(eq(local()), eq("extract:report"));
    }

    @Test
    public void test_index_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/").getPath().substring(1), "{}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.name).collect(toList());
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(1)));
    }

    @Test
    public void test_index_and_scan_default_directory() {
        RestAssert response = post("/api/task/batchUpdate/index/file", "{}");
        HashMap<String, String> properties = getDefaultProperties();
        properties.put("foo", "bar");

        response.should().respond(200).haveType("application/json");
        verify(taskFactory).createScanTask(local(), "extract:queue", Paths.get("/default/data/dir"), new PropertiesProvider(properties).getProperties());
    }

    @Test
    public void test_run_batch_search() {
        RestAssert response = post("/api/task/batchSearch", "{}");

        response.should().respond(200).haveType("application/json");
        verify(taskFactory).createBatchSearchLoop();
    }

    @Test
    public void test_index_and_scan_directory_with_options() {
        String path = getClass().getResource("/docs/").getPath();

        RestAssert response = post("/api/task/batchUpdate/index/" + path.substring(1),
                "{\"options\":{\"foo\":\"baz\",\"key\":\"val\"}}");

        response.should().haveType("application/json");
        HashMap<String, String> defaultProperties = getDefaultProperties();
        defaultProperties.put("foo", "baz");
        defaultProperties.put("key", "val");
        verify(taskFactory).createIndexTask(local(), "extract:queue", new PropertiesProvider(defaultProperties).getProperties());
        verify(taskFactory).createScanTask(local(), "extract:queue", Paths.get(path), new PropertiesProvider(defaultProperties).getProperties());
    }

    @Test
    public void test_index_queue_with_options() {
        RestAssert response = post("/api/task/batchUpdate/index", "{\"options\":{\"key1\":\"val1\",\"key2\":\"val2\"}}");

        response.should().haveType("application/json");
        verify(taskFactory).createIndexTask(local(), "extract:queue", new PropertiesProvider(new HashMap<String, String>() {{
            put("key1", "val1");
            put("key2", "val2");
        }}).getProperties());
        verify(taskFactory, never()).createScanTask(eq(local()), eq("extract:queue"), any(Path.class), any(Properties.class));
    }

    @Test
    public void test_scan_with_options() {
        String path = getClass().getResource("/docs/").getPath();
        RestAssert response = post("/api/task/batchUpdate/scan/" + path.substring(1),
                "{\"options\":{\"key\":\"val\",\"foo\":\"qux\"}}");

        ShouldChain responseBody = response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.name).collect(toList());
        assertThat(taskNames.size()).isEqualTo(1);
        responseBody.should().contain(format("{\"name\":\"%s\"", taskNames.get(0)));
        HashMap<String, String> defaultProperties = getDefaultProperties();
        defaultProperties.put("key", "val");
        defaultProperties.put("foo", "qux");
        verify(taskFactory).createScanTask(local(), "extract:queue", Paths.get(path), new PropertiesProvider(defaultProperties).getProperties());
        verify(taskFactory, never()).createIndexTask(any(User.class), anyString(), any(Properties.class));
    }

    @Test
    public void test_findNames_should_create_resume() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false}}");

        response.should().haveType("application/json");

        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.name).collect(toList());
        assertThat(taskNames.size()).isEqualTo(2);
        verify(taskFactory).createResumeNlpTask(local(), singleton(Pipeline.Type.EMAIL));

        ArgumentCaptor<Pipeline> pipelineArgumentCaptor = ArgumentCaptor.forClass(Pipeline.class);

        HashMap<String, String> properties = getDefaultProperties();
        properties.put("waitForNlpApp", "false");
        verify(taskFactory).createNlpTask(eq(local()), pipelineArgumentCaptor.capture(), eq(new PropertiesProvider(properties).getProperties()), any());
        assertThat(pipelineArgumentCaptor.getValue().getType()).isEqualTo(Pipeline.Type.EMAIL);
    }

    @Test
    public void test_findNames_with_options_should_merge_with_property_provider() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"waitForNlpApp\": false, \"key\":\"val\",\"foo\":\"loo\"}}");
        response.should().haveType("application/json");

        verify(taskFactory).createResumeNlpTask(local(), singleton(Pipeline.Type.EMAIL));

        ArgumentCaptor<Pipeline> pipelineCaptor = ArgumentCaptor.forClass(Pipeline.class);
        ArgumentCaptor<Properties> propertiesCaptor = ArgumentCaptor.forClass(Properties.class);
        verify(taskFactory).createNlpTask(eq(local()), pipelineCaptor.capture(), propertiesCaptor.capture(), any());
        assertThat(propertiesCaptor.getValue()).includes(entry("key", "val"), entry("foo", "loo"));

        assertThat(pipelineCaptor.getValue().getType()).isEqualTo(Pipeline.Type.EMAIL);
    }

    @Test
    public void test_findNames_with_resume_false_should_not_launch_resume_task() {
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"resume\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        verify(taskFactory, never()).createResumeNlpTask(null, singleton(Pipeline.Type.OPENNLP));
    }

    @Test
    public void test_findNames_with_sync_models_false() {
        AbstractModels.syncModels(true);
        RestAssert response = post("/api/task/findNames/EMAIL", "{\"options\":{\"syncModels\":\"false\", \"waitForNlpApp\": false}}");
        response.should().haveType("application/json");

        assertThat(AbstractModels.isSync()).isFalse();
    }

    @Test
    public void test_batch_download() {
        post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": \"*\" }}").
                should().haveType("application/json").
                should().contain("properties").
                should().contain("filename");
        verify(taskFactory).createDownloadRunner(eq(new BatchDownload(Collections.singletonList(project("test-datashare")), local(), "*", Paths.get("app", "tmp"), false)), any());
    }

    @Test
    public void test_batch_download_multiple_projects() {
        post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"project1\", \"project2\"], \"query\": \"*\" }}").
                should().haveType("application/json").
                should().contain("properties").
                should().contain("filename");
        verify(taskFactory).createDownloadRunner(eq(new BatchDownload(Arrays.asList(project("project1"), project("project2")), local(), "*", Paths.get("app", "tmp"), false)), any());
    }

    @Test
    public void test_batch_download_json_query() {
        post("/api/task/batchDownload", "{\"options\":{ \"projectIds\":[\"test-datashare\"], \"query\": {\"match_all\":{}} }}").
                should().haveType("application/json").
                should().contain("properties").
                should().contain("filename");

        verify(taskFactory).createDownloadRunner(eq(new BatchDownload(Collections.singletonList(project("test-datashare")), local(), "{\"match_all\":{}}", Paths.get("app", "tmp"), false)), any());
    }

    @Test
    public void test_clean_tasks() {
        post("/api/task/batchUpdate/index/file/" + getClass().getResource("/docs/doc.txt").getPath().substring(1), "{}").response();
        List<String> taskNames = taskManager.waitTasksToBeDone(1, SECONDS).stream().map(t -> t.name).collect(toList());

        ShouldChain responseBody = post("/api/task/clean", "{}").should().haveType("application/json");

        responseBody.should().contain(taskNames.get(0));
        responseBody.should().contain(taskNames.get(1));
        assertThat(taskManager.get()).isEmpty();
    }

    @Test
    public void test_stop_task() {
        TaskView<String> dummyTask = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        put("/api/task/stop/" + dummyTask.name).should().respond(200).contain("true");

        assertThat(taskManager.get(dummyTask.name).getState()).isEqualTo(TaskView.State.CANCELLED);
        get("/api/task/all").should().respond(200).contain("\"state\":\"CANCELLED\"");
    }

    @Test
    public void test_stop_unknown_task() {
        put("/api/task/stop/foobar").should().respond(404);
    }

    @Test
    public void test_stop_all() {
        TaskView<String> t1 = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        TaskView<String> t2 = taskManager.startTask(() -> {
            Thread.sleep(10000);
            return "ok";
        });
        put("/api/task/stopAll").should().respond(200).
                contain(t1.name + "\":true").
                contain(t2.name + "\":true");

        assertThat(taskManager.get(t1.name).getState()).isEqualTo(TaskView.State.CANCELLED);
        assertThat(taskManager.get(t2.name).getState()).isEqualTo(TaskView.State.CANCELLED);
    }

    @Test
    public void test_stop_all_filters_running_tasks() {
        taskManager.startTask(() -> "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");
    }

    @Test
    public void test_clear_done_tasks() {
        taskManager.startTask(() -> "ok");
        taskManager.waitTasksToBeDone(1, SECONDS);

        put("/api/task/stopAll").should().respond(200).contain("{}");

        assertThat(taskManager.clearDoneTasks()).hasSize(1);
        assertThat(taskManager.get()).hasSize(0);
    }

    @NotNull
    private HashMap<String, String> getDefaultProperties() {
        return new HashMap<String, String>() {{
            put("dataDir", "/default/data/dir");
            put("foo", "bar");
        }};
    }

    private void init(TaskFactory taskFactory) {
        reset(taskFactory);
        when(taskFactory.createIndexTask(any(), any(), any())).thenReturn(mock(IndexTask.class));
        when(taskFactory.createBatchSearchLoop()).thenReturn(mock(BatchSearchLoop.class));
        when(taskFactory.createScanTask(any(), any(), any(), any())).thenReturn(mock(ScanTask.class));
        when(taskFactory.createDeduplicateTask(any(), any())).thenReturn(mock(DeduplicateTask.class));
        when(taskFactory.createDownloadRunner(any(), any())).thenReturn(mock(BatchDownloadRunner.class));
        when(taskFactory.createScanIndexTask(any(), any())).thenReturn(mock(ScanIndexTask.class));
        when(taskFactory.createResumeNlpTask(any(), eq(singleton(Pipeline.Type.EMAIL)))).thenReturn(mock(ResumeNlpTask.class));
        when(taskFactory.createNlpTask(any(), any())).thenReturn(mock(NlpApp.class));
        when(taskFactory.createNlpTask(any(), any(), any(), any())).thenReturn(mock(NlpApp.class));
    }
}
