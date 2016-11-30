/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.forge.devops;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;

import io.fabric8.devops.ProjectConfig;
import io.fabric8.devops.ProjectConfigs;
import io.fabric8.devops.connector.DevOpsConnector;
import io.fabric8.forge.addon.utils.CommandHelpers;
import io.fabric8.forge.addon.utils.StopWatch;
import io.fabric8.forge.devops.dto.PipelineDTO;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.letschat.LetsChatClient;
import io.fabric8.letschat.LetsChatKubernetes;
import io.fabric8.letschat.RoomDTO;
import io.fabric8.taiga.ProjectDTO;
import io.fabric8.taiga.TaigaClient;
import io.fabric8.taiga.TaigaKubernetes;
import io.fabric8.utils.Files;
import io.fabric8.utils.GitHelpers;
import io.fabric8.utils.IOHelpers;
import io.fabric8.utils.Strings;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.facets.MetadataFacet;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.context.UINavigationContext;
import org.jboss.forge.addon.ui.input.InputComponent;
import org.jboss.forge.addon.ui.input.UICompleter;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.NavigationResult;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.addon.ui.wizard.UIWizardStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DevOpsEditTwoStep extends AbstractDevOpsCommand {
    private static final transient Logger LOG = LoggerFactory.getLogger(DevOpsEditTwoStep.class);

    private static final boolean copyPipelineToProject = true;

    @Inject
    @WithAttributes(label = "Chat room", required = false, description = "Name of chat room to use for this project")
    private UIInput<String> chatRoom;

    @Inject
    @WithAttributes(label = "IssueTracker Project name", required = false, description = "Name of the issue tracker project")
    private UIInput<String> issueProjectName;

    @Inject
    @WithAttributes(label = "Code review", required = false, description = "Enable code review of all commits")
    private UIInput<Boolean> codeReview;

    private String namespace = KubernetesHelper.defaultNamespace();
    private LetsChatClient letsChat;
    private TaigaClient taiga;
    private List<InputComponent> inputComponents;


    @Override
    public UICommandMetadata getMetadata(UIContext context) {
        return Metadata.forCommand(getClass())
                .category(Categories.create(AbstractDevOpsCommand.CATEGORY))
                .name(AbstractDevOpsCommand.CATEGORY + ": Configure Tooling")
                .description("Configure the Project Tooling for the new project");
    }

    @Override
    public void initializeUI(UIBuilder builder) throws Exception {
        StopWatch watch = new StopWatch();

        final UIContext context = builder.getUIContext();
/*        chatRoom.setCompleter(new UICompleter<String>() {
            @Override
            public Iterable<String> getCompletionProposals(UIContext context, InputComponent<?, String> input, String value) {
                // TODO: call only once to init getChatRoomNames
                return filterCompletions(getChatRoomNames(), value);
            }
        });
        issueProjectName.setCompleter(new UICompleter<String>() {
            @Override
            public Iterable<String> getCompletionProposals(UIContext context, InputComponent<?, String> input, String value) {
                // TODO: call only once to init getIssueProjectNames
                return filterCompletions(getIssueProjectNames(), value);
            }
        });*/

        // lets initialise the data from the current config if it exists
        ProjectConfig config = (ProjectConfig) context.getAttributeMap().get("projectConfig");
        if (config != null) {
            CommandHelpers.setInitialComponentValue(chatRoom, config.getChatRoom());
            CommandHelpers.setInitialComponentValue(issueProjectName, config.getIssueProjectName());
            CommandHelpers.setInitialComponentValue(codeReview, config.getCodeReview());
        }

        inputComponents = new ArrayList<>();

        inputComponents.addAll(CommandHelpers.addInputComponents(builder, chatRoom, issueProjectName, codeReview));

        LOG.info("initializeUI took " + watch.taken());
    }

    public static Iterable<String> filterCompletions(Iterable<String> values, String inputValue) {
        boolean ignoreFilteringAsItBreaksHawtio = true;
        if (ignoreFilteringAsItBreaksHawtio) {
            return values;
        } else {
            List<String> answer = new ArrayList<>();
            String lowerInputValue = inputValue.toLowerCase();
            for (String value : values) {
                if (value != null) {
                    if (value.toLowerCase().contains(lowerInputValue)) {
                        answer.add(value);
                    }
                }
            }
            return answer;
        }
    }

    @Override
    public Result execute(UIExecutionContext context) throws Exception {
        StopWatch watch = new StopWatch();

        LOG.info("Configuring project with selected dev-ops settings. This can take a while ...");

        CommandHelpers.putComponentValuesInAttributeMap(context, inputComponents);

        String fileName = ProjectConfigs.FILE_NAME;
        Project project = getSelectedProject(context);
        File configFile = getProjectConfigFile(context.getUIContext(), getSelectedProject(context));
        if (configFile == null) {
            // lets not fail as we typically want to execute SaveDevOpsStep next...
            return Results.success();
        }
        ProjectConfig config = null;
        boolean hasFile = false;
        if (configFile.exists()) {
            config = ProjectConfigs.parseProjectConfig(configFile);
            hasFile = true;
        }
        if (config == null) {
            config = new ProjectConfig();
        }

        updateConfiguration(context, config);
        LOG.info("Using ProjectConfig: " + config);

        String message;
        if (config.isEmpty() && !hasFile) {
            message = "No " + fileName + " need be generated as there is no configuration";
            return Results.success(message);
        } else {
            String operation = "Updated";
            if (!configFile.exists()) {
                operation = "Created";
            }
            ProjectConfigs.saveConfig(config, configFile);
            message = operation + " " + fileName;
        }

        // now lets update the devops stuff
        UIContext uiContext = context.getUIContext();
        Map<Object, Object> attributeMap = uiContext.getAttributeMap();

        String localGitUrl = getStringAttribute(attributeMap, "localGitUrl");
        String gitUrl = getStringAttribute(attributeMap, "gitUrl");
        if (Strings.isNullOrBlank(gitUrl)) {
            gitUrl = getStringAttribute(attributeMap, "gitAddress");
        }

        Object object = attributeMap.get(Project.class);
        String user = getStringAttribute(attributeMap, "gitUser");
        String named = getStringAttribute(attributeMap, "projectName");

        File basedir = CommandHelpers.getBaseDir(project);
        if (basedir == null && configFile != null) {
            basedir = configFile.getParentFile();
        }

        if (object instanceof Project) {
            Project newProject = (Project) object;
            MetadataFacet facet = newProject.getFacet(MetadataFacet.class);
            if (facet != null) {
                if (Strings.isNullOrBlank(named)) {
                    named = facet.getProjectName();
                }
                if (Strings.isNullOrBlank(gitUrl)) {
                    String address = getStringAttribute(attributeMap, "gitAddress");
                    gitUrl = address + user + "/" + named + ".git";
                }
            } else {
                LOG.error("No MetadataFacet for newly created project " + newProject);
            }
        } else {
            // updating an existing project - so lets try find the git url from the current source code
            if (Strings.isNullOrBlank(gitUrl)) {
                gitUrl = GitHelpers.extractGitUrl(basedir);
            }
            if (basedir != null) {
                if (Strings.isNullOrBlank(named)) {
                    named = basedir.getName();
                }
            }
        }

        // lets default the environments from the pipeline
        context.getUIContext().getAttributeMap().get("pipeline");
        PipelineDTO pipelineValue = (PipelineDTO) context.getUIContext().getAttributeMap().get("pipeline");
        LOG.info("Using pipeline " + pipelineValue);
        String buildName = config.getBuildName();
        if (Strings.isNotBlank(buildName)) {
            if (pipelineValue != null) {
                List<String> environments = pipelineValue.getEnvironments();
                if (environments == null) {
                    environments = new ArrayList<>();
                }
                LinkedHashMap<String, String> environmentMap = new LinkedHashMap<>();
                if (environments.isEmpty()) {
                    environmentMap.put("Current", namespace);
                } else {
                    for (String environment : environments) {
                        String envNamespace = namespace + "-" + environment.toLowerCase();
                        environmentMap.put(environment, envNamespace);
                    }
                }
                config.setEnvironments(environmentMap);
            }
        }
        LOG.info("Configured project " + buildName + " environments: " + config.getEnvironments());
        ProjectConfigs.defaultEnvironments(config, namespace);

        String projectName = config.getBuildName();
        if (Strings.isNullOrBlank(projectName)) {
            projectName = named;
            config.setBuildName(projectName);
        }
        LOG.info("Project name is: " + projectName);

        // if we already have a Jenkinsfile after importing...
        // otherwise copy the selected flow if it was selected and is
        // eligible for copying
        boolean hasLocalJenkinsFile = (boolean) context.getUIContext().getAttributeMap().get("hasJenkinsFile");
        if (pipelineValue == null && hasLocalJenkinsFile && !config.isUseLocalFlow()) {
            config.setUseLocalFlow(true);
        }
        else if (copyPipelineToProject) {
            if (basedir == null || !basedir.isDirectory()) {
                LOG.warn("Cannot copy the pipeline to the project as no basedir!");
            } else {
                String flow = null;
                PipelineDTO pipelineDTO = pipelineValue;
                if (pipelineDTO != null) {
                    flow = pipelineDTO.getValue();
                }
                if (Strings.isNullOrBlank(flow)) {
                    LOG.warn("Cannot copy the pipeline to the project as no pipeline selected!");
                } else if (config.isUseLocalFlow()) {
                    LOG.warn("Cannot copy the pipeline to the project as it already exists in the project!");
                } else {
                    String flowText = getFlowContent(flow, uiContext);
                    if (Strings.isNullOrBlank(flowText))  {
                        LOG.warn("Cannot copy the pipeline to the project as no pipeline text could be loaded!");
                    } else {
                        flowText = Strings.replaceAllWithoutRegex(flowText, "GIT_URL", "'" + gitUrl + "'");
                        File newFile = new File(basedir, ProjectConfigs.LOCAL_FLOW_FILE_NAME);
                        Files.writeToFile(newFile, flowText.getBytes());
                        LOG.info("Written pipeline to " + newFile);
                        config.setPipeline(null);
                        config.setUseLocalFlow(true);
                    }
                }
            }
        }

        // lets check that the localGitUrl has a path
        if (Strings.isNotBlank(localGitUrl) && Strings.isNotBlank(gitUrl)) {
            try {
                URL localURL = new URL(localGitUrl);
                URL remoteURL = new URL(gitUrl);

                String localPath = localURL.getPath();
                if (isEmptyUrlPath(localPath)) {
                    String remotePath = remoteURL.getPath();
                    if (!isEmptyUrlPath(remotePath)) {
                        localGitUrl = localURL.toURI().resolve(remotePath).toString();
                        LOG.info("using cluster local git URL: " + localGitUrl);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to add path to the localGitUrl: " + e, e);
                localGitUrl = null;
            }
        }

        final DevOpsConnector connector = new DevOpsConnector();
        connector.setProjectConfig(config);
        connector.setTryLoadConfigFileFromRemoteGit(false);
        connector.setUsername(user);
        connector.setPassword(getStringAttribute(attributeMap, "gitPassword"));
        connector.setBranch(getStringAttribute(attributeMap, "gitBranch", "master"));
        connector.setBasedir(basedir);
        connector.setGitUrl(gitUrl);
        connector.setLocalGitUrl(localGitUrl);
        connector.setRepoName(named);

        connector.setRegisterWebHooks(true);

        // lets not trigger the jenkins webhook yet as the git push should trigger the build
        connector.setTriggerJenkinsJob(false);

        LOG.info("Using connector: " + connector);
        try {
            connector.execute();
        } catch (Exception e) {
            LOG.error("Failed to update DevOps resources: " + e, e);
            return Results.fail("Cannot update dev-ops configuration due " + e.getMessage() + ". See more details in the logs from the fabric8-forge pod.");
        }

        LOG.info("Execute took " + watch.taken());
        LOG.info("Project successfully configured with the selected dev-ops settings.");

        return Results.success(message);
    }

    private String getStringAttribute(Map<Object, Object> attributeMap, String name, String defaultValue) {
        String answer = getStringAttribute(attributeMap, name);
        return Strings.isNullOrBlank(answer) ? defaultValue : answer;
    }

    private String getStringAttribute(Map<Object, Object> attributeMap, String name) {
        Object value = attributeMap.get(name);
        if (value != null) {
            return value.toString();
        }
        return null;
    }

    private String getFlowContent(String flow, UIContext context) {
        File dir = getJenkinsWorkflowFolder(context);
        if (dir != null) {
            File file = new File(dir, flow);
            if (file.isFile() && file.exists()) {
                try {
                    return IOHelpers.readFully(file);
                } catch (IOException e) {
                    LOG.warn("Failed to load local pipeline " + file + ". " + e, e);
                }
            }
        }
        return null;
    }

    private File getJenkinsWorkflowFolder(UIContext context) {
        File dir = null;
        Object workflowFolder = context.getAttributeMap().get("jenkinsfilesFolder");
        if (workflowFolder instanceof File) {
            dir = (File) workflowFolder;
        }
        return dir;
    }

    private static boolean isEmptyUrlPath(String localPath) {
        return localPath.length() == 0 || "/".equals(localPath);
    }

    private Iterable<String> getIssueProjectNames() {
        Set<String> answer = new TreeSet<>();
        try {
            TaigaClient letschat = getTaiga();
            if (letschat != null) {
                List<ProjectDTO> projects = null;
                try {
                    projects = letschat.getProjects();
                } catch (Exception e) {
                    LOG.warn("Failed to load chat projects! " + e, e);
                }
                if (projects != null) {
                    for (ProjectDTO project : projects) {
                        String name = project.getName();
                        if (name != null) {
                            answer.add(name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to get issue project names: " + e, e);
        }
        return answer;
    }

    private Iterable<String> getChatRoomNames() {
        Set<String> answer = new TreeSet<>();
        try {
            LetsChatClient letschat = getLetsChat();
            if (letschat != null) {
                List<RoomDTO> rooms = null;
                try {
                    rooms = letschat.getRooms();
                } catch (Exception e) {
                    LOG.warn("Failed to load chat rooms! " + e, e);
                }
                if (rooms != null) {
                    for (RoomDTO room : rooms) {
                        String name = room.getSlug();
                        if (name != null) {
                            answer.add(name);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to find chat room names: " + e, e);
        }
        return answer;
    }

    public LetsChatClient getLetsChat() {
        if (letsChat == null) {
            letsChat = LetsChatKubernetes.createLetsChat(getKubernetes());
        }
        return letsChat;
    }

    public void setLetsChat(LetsChatClient letsChat) {
        this.letsChat = letsChat;
    }

    public TaigaClient getTaiga() {
        if (taiga == null) {
            taiga = TaigaKubernetes.createTaiga(getKubernetes(), namespace);
        }
        return taiga;
    }

    public void setTaiga(TaigaClient taiga) {
        this.taiga = taiga;
    }

}
