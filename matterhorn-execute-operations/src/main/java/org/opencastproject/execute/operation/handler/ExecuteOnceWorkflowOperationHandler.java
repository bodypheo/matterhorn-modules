/**
 *  Copyright 2009, 2010 The Regents of the University of California
 *  Licensed under the Educational Community License, Version 2.0
 *  (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at
 *
 *  http://www.osedu.org/licenses/ECL-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS IS"
 *  BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 *  or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 *
 */
package org.opencastproject.execute.operation.handler;

import org.opencastproject.execute.api.ExecuteException;
import org.opencastproject.execute.api.ExecuteService;
import org.opencastproject.inspection.api.MediaInspectionException;
import org.opencastproject.inspection.api.MediaInspectionService;
import org.opencastproject.job.api.Job;
import org.opencastproject.job.api.JobBarrier;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageElement;
import org.opencastproject.mediapackage.MediaPackageElementFlavor;
import org.opencastproject.mediapackage.MediaPackageElementParser;
import org.opencastproject.mediapackage.MediaPackageException;
import org.opencastproject.util.NotFoundException;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationInstance;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workflow.api.WorkflowOperationResultImpl;
import org.opencastproject.workspace.api.Workspace;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Runs an operation once with using elements within a certain MediaPackage as parameters 
 */
public class ExecuteOnceWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

  /** The logging facility */
  private static final Logger logger = LoggerFactory.getLogger(ExecuteOnceWorkflowOperationHandler.class);

  /** Property containing the command to run */
  public static final String EXEC_PROPERTY = "exec";

  /** Property containing the list of command parameters */
  public static final String PARAMS_PROPERTY = "params";

  /** Property containing the "flavor" that a mediapackage elements must have in order to be used as input arguments */
  public static final String SOURCE_FLAVOR_PROPERTY = "source-flavor";

  /** Property containing the filename of the elements created by this operation */
  public static final String OUTPUT_FILENAME_PROPERTY = "output-filename";

  /** Property containing the expected type of the element generated by this operation */
  public static final String EXPECTED_TYPE_PROPERTY = "expected-type";

  /** Property containing the tags that must exist on a mediapackage element for the element to be used as an input arguments */
  public static final String SOURCE_TAGS_PROPERTY = "source-tags";

  /** Property containing the flavor that the resulting mediapackage elements will be assigned */
  public static final String TARGET_FLAVOR_PROPERTY = "target-flavor";

  /** Property containing the tags that the resulting mediapackage elements will be assigned */
  public static final String TARGET_TAGS_PROPERTY = "target-tags";

  /** The text analyzer */
  protected ExecuteService executeService;

  /** Reference to the media inspection service */
  private MediaInspectionService inspectionService = null;

  /** The workspace service */
  protected Workspace workspace; 

  /** The configuration options for this handler */
  private static final SortedMap<String, String> CONFIG_OPTIONS;

  static {
    CONFIG_OPTIONS = new TreeMap<String, String>();
    CONFIG_OPTIONS.put(EXEC_PROPERTY, "The full path the executable to run");
    CONFIG_OPTIONS.put(PARAMS_PROPERTY, "Space separated list of command line parameters to pass to the executable')");
    CONFIG_OPTIONS.put(OUTPUT_FILENAME_PROPERTY, "The name of the elements created by this operation");
    CONFIG_OPTIONS.put(EXPECTED_TYPE_PROPERTY,
            "The type of the element returned by this operation. Accepted values are: manifest, timeline, track, catalog, attachment, other");
    CONFIG_OPTIONS.put(TARGET_FLAVOR_PROPERTY, "The flavor that the resulting mediapackage elements will be assigned");
    CONFIG_OPTIONS.put(TARGET_TAGS_PROPERTY, "The tags that the resulting mediapackage elements will be assigned");
  }

  /**
   * {@inheritDoc}
   * 
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#start(org.opencastproject.workflow.api.WorkflowInstance, JobContext)
   */
  @Override
  public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context) throws WorkflowOperationException {

    MediaPackage mediaPackage = workflowInstance.getMediaPackage();
    WorkflowOperationInstance operation = workflowInstance.getCurrentOperation();

    logger.debug("Running execute workflow operation with ID {}", operation.getId());

    // Get operation parameters
    String exec = StringUtils.trimToNull(operation.getConfiguration(EXEC_PROPERTY));
    String params = StringUtils.trimToNull(operation.getConfiguration(PARAMS_PROPERTY));
    String targetFlavorStr = StringUtils.trimToNull(operation.getConfiguration(TARGET_FLAVOR_PROPERTY));
    String targetTags = StringUtils.trimToNull(operation.getConfiguration(TARGET_TAGS_PROPERTY));
    String outputFilename = StringUtils.trimToNull(operation.getConfiguration(OUTPUT_FILENAME_PROPERTY));
    String expectedTypeStr = StringUtils.trimToNull(operation.getConfiguration(EXPECTED_TYPE_PROPERTY));

    // Unmarshall target flavor
    MediaPackageElementFlavor targetFlavor = null;
    if (targetFlavorStr != null)
      targetFlavor = MediaPackageElementFlavor.parseFlavor(targetFlavorStr);

    // Unmarshall expected mediapackage element type
    MediaPackageElement.Type expectedType = null;
    if (expectedTypeStr != null) {
      for (MediaPackageElement.Type type : MediaPackageElement.Type.values())
        if (type.toString().equalsIgnoreCase(expectedTypeStr)) {
          expectedType = type;
          break;
        }

      if (expectedType == null)
        throw new WorkflowOperationException("'" + expectedTypeStr + "' is not a valid element type");
    }

    // Process the result element
    MediaPackageElement resultElement = null;
    
    try{
      Job job = executeService.execute(exec, params, mediaPackage, outputFilename, expectedType);

      // Wait for all jobs to be finished                                                                                                                                                                                              
      if (!waitForStatus(job).isSuccess())
        throw new WorkflowOperationException("Execute operation failed");

      if (StringUtils.trimToNull(job.getPayload()) != null) {

        resultElement = MediaPackageElementParser.getFromXml(job.getPayload());

        if (resultElement.getElementType() == MediaPackageElement.Type.Track) {
          // Have the track inspected and return the result
          Job inspectionJob = null;
          inspectionJob = inspectionService.inspect(resultElement.getURI());
          JobBarrier barrier = new JobBarrier(serviceRegistry, inspectionJob);
          if (!barrier.waitForJobs().isSuccess()) {
            throw new ExecuteException("Media inspection of " + resultElement.getURI() + " failed");
          }

          resultElement = MediaPackageElementParser.getFromXml(inspectionJob.getPayload());
        }

        // Store new element to mediaPackage
        mediaPackage.add(resultElement);
        // Store new element to mediaPackage
        URI uri = workspace.moveTo(resultElement.getURI(), mediaPackage.getIdentifier().toString(),
                resultElement.getIdentifier(), outputFilename);
        resultElement.setURI(uri);

        // Set new flavor
        if (targetFlavor != null)
          resultElement.setFlavor(targetFlavor);

        // Set new tags
        if (targetTags != null) 
          // Assume the tags starting with "-" means we want to eliminate such tags form the result element
          for (String tag : asList(targetTags)) {
            if (tag.startsWith("-"))
              // We remove the tag resulting from stripping all the '-' characters at the beginning of the tag
              resultElement.removeTag(tag.replaceAll("^-+", ""));
            else
              resultElement.addTag(tag);              
          }

      } 

      WorkflowOperationResult result = createResult(mediaPackage, Action.CONTINUE, job.getQueueTime());
      logger.debug("Execute operation {} completed", operation.getId());

      return result;

    } catch (ExecuteException e) {
      throw new WorkflowOperationException(e);
    } catch (MediaPackageException e) {
      throw new WorkflowOperationException("Some result element couldn't be serialized", e);
    } catch (NotFoundException e) {
      throw new WorkflowOperationException("Could not find mediapackage", e);
    } catch (IOException e) {
      throw new WorkflowOperationException("Error unmarshalling a result mediapackage element", e);
    } catch (MediaInspectionException e) {
      throw new WorkflowOperationException("Media inspection of " + resultElement.getURI() + " failed", e);
    }

  }

  /**
   * {@inheritDoc}
   * 
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#skip(org.opencastproject.workflow.api.WorkflowInstance, JobContext)
   */
  @Override
  public WorkflowOperationResult skip(WorkflowInstance workflowInstance, JobContext context) throws WorkflowOperationException {
    return new WorkflowOperationResultImpl(workflowInstance.getMediaPackage(), null, Action.SKIP, 0);
  }

  @Override
  public String getId() {
    return "execute";
  }

  @Override
  public String getDescription() {
    return "Executes command line workflow operations in workers";
  }

  @Override
  public void destroy(WorkflowInstance workflowInstance, JobContext context) throws WorkflowOperationException {
    // Do nothing (nothing to clean up, the command line program should do this itself)
  }


  /**
   * {@inheritDoc}
   * 
   * @see org.opencastproject.workflow.api.WorkflowOperationHandler#getConfigurationOptions()
   */
  @Override
  public SortedMap<String, String> getConfigurationOptions() {
    return CONFIG_OPTIONS;
  }

  /**
   * Sets the service
   * 
   * @param service
   */
  public void setExecuteService(ExecuteService service) {
    this.executeService = service;
  }

  /**
   * Sets a reference to the workspace service.
   * 
   * @param workspace
   */
  public void setWorkspace(Workspace workspace) {
    this.workspace = workspace;
  }

  /**
   * Sets the media inspection service
   * 
   * @param mediaInspectionService
   *          an instance of the media inspection service
   */
  protected void setMediaInspectionService(MediaInspectionService mediaInspectionService) {
    this.inspectionService = mediaInspectionService;
  } 
}
