package com.dtolabs.rundeck.plugin.smb;


import com.dtolabs.rundeck.core.Constants;
import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.impl.common.BaseFileCopier;
import com.dtolabs.rundeck.core.execution.script.ScriptfileUtils;
import com.dtolabs.rundeck.core.execution.service.DestinationFileCopier;
import com.dtolabs.rundeck.core.execution.service.FileCopier;
import com.dtolabs.rundeck.core.execution.service.FileCopierException;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyUtil;
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants;
import com.dtolabs.rundeck.core.storage.ResourceMeta;
import com.dtolabs.rundeck.core.tasks.net.SSHTaskBuilder;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder;
import com.dtolabs.rundeck.plugins.util.PropertyBuilder;
import com.hierynomus.ntlm.messages.WindowsVersion;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.connection.ConnectionInfo;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.utils.SmbFiles;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.nio.file.Files;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Echo;
import org.apache.tools.ant.taskdefs.Sequential;
import org.rundeck.storage.api.Path;
import org.rundeck.storage.api.PathUtil;
import org.rundeck.storage.api.StorageException;


/**
 * SmbFileCopier is ...
 *
 * @author Espen Blikstad <a href="mailto:espen@blikstad.no">espen@blikstad.no</a>
 */
@Plugin(name = SmbFileCopier.SERVICE_PROVIDER_TYPE, service = "FileCopier")
public class SmbFileCopier extends BaseFileCopier implements FileCopier, Describable, DestinationFileCopier {
    public static final String SERVICE_PROVIDER_TYPE = "smb";
    private static final String CONFIG_PASSWORD_STORAGE_PATH = "passwordStoragePath";
    public static final String SMB_PASSWORD_STORAGE_PATH = "smb-password-storage-path";
    public static final String SMB_RETRY_MAX = "smb-retry-max";
    public static final String SMB_RETRY_DELAY = "smb-retry-delay";
    
    public static final String SMB_USER = "smb-user";

    
    private static final String PROJ_PROP_PREFIX = "project.";
    private static final String FWK_PROP_PREFIX = "framework.";

    //Config properties for GUI
    private static final String CONFIG_RETRY_MAX = "retry-max";
    private static final String CONFIG_RETRY_DELAY = "retry-delay";

    public static final int DEFAULT_SMB_RETRY_MAX = 3; 
    public static final int DEFAULT_SMB_RETRY_DELAY = 15; 
    
    static final Description DESC = DescriptionBuilder.builder()
        .name(SERVICE_PROVIDER_TYPE)
        .title("SMB")
        .description("Copies a script file to a remote node via SMB.")
        .property(PropertyUtil.longProp(CONFIG_RETRY_MAX, "SMB retry maximum attempts", "The maximum number of retries, " + 
                "in case of intermittent transport errors. Default: 3.", false, null)) 
        .property(PropertyUtil.longProp(CONFIG_RETRY_DELAY, "SMB retry delay", "The retry delay, " + 
                "in seconds. Default: 5 (seconds).", false, null)) 
        .property(
                PropertyBuilder.builder()
                               .string(CONFIG_PASSWORD_STORAGE_PATH)
                               .title("SMB Password Storage Path")
                               .description(
                            		   "Path to the Password to use within Rundeck Storage. E.g. \"keys/path/my.password\". Can be overridden by a Node attribute named 'ssh-password-storage-path'."
                               )
                               .renderingOption(
                                       StringRenderingConstants.SELECTION_ACCESSOR_KEY,
                                       StringRenderingConstants.SelectionAccessor.STORAGE_PATH
                               )
                               .renderingOption(
                                       StringRenderingConstants.STORAGE_PATH_ROOT_KEY,
                                       "keys"
                               )
                               .renderingOption(
                                       StringRenderingConstants.STORAGE_FILE_META_FILTER_KEY,
                                       "Rundeck-data-type=password"
                               )
                               .build()
      )
        .mapping(CONFIG_PASSWORD_STORAGE_PATH, FWK_PROP_PREFIX + SMB_PASSWORD_STORAGE_PATH)
        .mapping(CONFIG_PASSWORD_STORAGE_PATH, PROJ_PROP_PREFIX + SMB_PASSWORD_STORAGE_PATH)        
        .mapping(CONFIG_RETRY_MAX, FWK_PROP_PREFIX + SMB_RETRY_MAX)
        .mapping(CONFIG_RETRY_MAX, PROJ_PROP_PREFIX + SMB_RETRY_MAX)        
        .mapping(CONFIG_RETRY_DELAY, FWK_PROP_PREFIX + SMB_RETRY_DELAY)
        .mapping(CONFIG_RETRY_DELAY, PROJ_PROP_PREFIX + SMB_RETRY_DELAY)        
        .build();


    public Description getDescription() {
        return DESC;
    }

    public static enum Reason implements FailureReason {
        CopyFileFailed,
    }


    private Framework framework;
    private String frameworkProject;

    
    public Framework getFramework() {
        return framework;
    }

    public String getFrameworkProject() {
        return frameworkProject;
    }


    static String nonBlank(String input) {
        if (null == input || "".equals(input.trim())) {
            return null;
        } else {
            return input.trim();
        }
    }

    private static int resolveIntProperty( 
            final String attribute, 
            final int defaultValue, 
            final INodeEntry iNodeEntry, 
            final String frameworkProject, 
            final Framework framework 
    ) throws ConfigurationException 
    { 
        int value = defaultValue; 
        final String string = resolveProperty(attribute, null, iNodeEntry, frameworkProject, framework); 
        if (null != string) { 
            try { 
                value = Integer.parseInt(string); 
            } catch (NumberFormatException e) { 
                throw new ConfigurationException("Not a valid integer: " + attribute + ": " + string); 
            } 
        } 
        return value; 
    } 
    
    /**
     * Resolve a node/project/framework property by first checking node attributes named X, then project properties
     * named "project.X", then framework properties named "framework.X". If none of those exist, return the default
     * value
     */
    private static String resolveProperty(
            final String nodeAttribute,
            final String defaultValue,
            final INodeEntry node,
            final String frameworkProject,
            final Framework framework
    )
    {
        if (null != node.getAttributes().get(nodeAttribute)) {
            return node.getAttributes().get(nodeAttribute);
        } else if (
                framework.hasProjectProperty(PROJ_PROP_PREFIX + nodeAttribute, frameworkProject)
                && !"".equals(framework.getProjectProperty(frameworkProject, PROJ_PROP_PREFIX + nodeAttribute))
                ) {
            return framework.getProjectProperty(frameworkProject, PROJ_PROP_PREFIX + nodeAttribute);
        } else if (framework.hasProperty(FWK_PROP_PREFIX + nodeAttribute)) {
            return framework.getProperty(FWK_PROP_PREFIX + nodeAttribute);
        } else {
            return defaultValue;
        }
    }
    

    public SmbFileCopier(Framework framework) {
        this.framework = framework;
    }

    public String copyFileStream(final ExecutionContext context, InputStream input, INodeEntry node) throws
                                                                                                     FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFileStream(final ExecutionContext context, InputStream input, INodeEntry node)");


        return copyFile(context, null, input, null, node);
    }

    public String copyFile(final ExecutionContext context, File scriptfile, INodeEntry node) throws
                                                                                             FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFile(final ExecutionContext context, File scriptfile, INodeEntry node)");

        return copyFile(context, scriptfile, null, null, node);
    }

    public String copyScriptContent(ExecutionContext context, String script, INodeEntry node) throws
                                                                                              FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyScriptContent(ExecutionContext context, String script, INodeEntry node)");

        return copyFile(context, null, null, script, node);
    }


    private String copyFile(final ExecutionContext context, final File scriptfile, final InputStream input,
                            final String script, final INodeEntry node) throws FileCopierException {
        return copyFile(context, scriptfile, input, script, node, null);

    }

    private String getUsername(final INodeEntry node) {
        final String user;
        if (null != nonBlank(node.getUsername()) || node.containsUserName()) {
            user = nonBlank(node.getUsername());

        } else {
            System.err.println("get smb-user");
            user = resolveProperty(SMB_USER, null, node, getFrameworkProject(), getFramework());
        }

        //if (null != user && user.contains("${")) {
        //    return DataContextUtils.replaceDataReferences(user, getContext().getDataContext());
        //}
        return user;
    }
    
    private String getPassword(final ExecutionContext context, final INodeEntry node)  throws ConfigurationException{
        //look for storage option
    	
        String storagePath = resolveProperty(SMB_PASSWORD_STORAGE_PATH, null,
                node, getFrameworkProject(), getFramework());
        if(null!=storagePath){
            //look up storage value
            if (storagePath.contains("${")) {
                storagePath = DataContextUtils.replaceDataReferences(
                        storagePath,
                        context.getDataContext()
                );
            }
            Path path = PathUtil.asPath(storagePath);
            
            try {
                ResourceMeta contents = context.getStorageTree().getResource(path)
                        .getContents();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                contents.writeContent(byteArrayOutputStream);
                return new String(byteArrayOutputStream.toByteArray());
            } catch (StorageException e) {
                throw new ConfigurationException("Failed to read the SMB password for " +
                        "storage path: " + storagePath + ": " + e.getMessage());
            } catch (IOException e) {
                throw new ConfigurationException("Failed to read the SMB password for " +
                        "storage path: " + storagePath + ": " + e.getMessage());
            }
        }
        //else look up option value
       // final String passwordOption = resolveProperty(WINRM_PASSWORD_OPTION,
       //         DEFAULT_WINRM_PASSWORD_OPTION, getNode(),
       //         getFrameworkProject(), getFramework());
       // return evaluateSecureOption(passwordOption, getContext());
        return null;
    }


    private String copyFile(
            final ExecutionContext context,
            final File scriptfile,
            final InputStream input,
            final String script,
            final INodeEntry node,
            final String destinationPath
    ) throws FileCopierException {

        //Project project = new Project();

        final String remotefile;
        if(null==destinationPath) {
            remotefile = generateRemoteFilepathForNode(node, (null != scriptfile ? scriptfile.getName()
                    : "dispatch-script"));
        }else {
            remotefile = destinationPath;
        }
        //write to a local temp file or use the input file
        final File localTempfile =
                null != scriptfile ?
                        scriptfile :
                        writeTempFile(
                                context,
                                scriptfile,
                                input,
                                script
                        );

        
        
        String logprompt = "[" + SERVICE_PROVIDER_TYPE + ":" + node.getNodename() + "] ";
        
        String username;
        String domain = null;
        username = getUsername(node);
        if(username.contains("\\")) {
        	domain = username.split("\\")[0];
        	username = username.split("\\")[1];
        }
        else if(username.contains("@")) {
        	domain = username.split("@")[1];        	
        	username = username.split("@")[0];
        }

        
        String password = null;
        
        frameworkProject = context.getFrameworkProject();
        try{
        password = getPassword(context, node);
        }catch(ConfigurationException E){
            System.err.println("ouch");        	
        }

        int retryMax = 0;
        int retryDelay = 0;
        try {
        	retryMax = resolveIntProperty(SMB_RETRY_MAX, DEFAULT_SMB_RETRY_MAX, node, 
                getFrameworkProject(), getFramework()); 
        	retryDelay = resolveIntProperty(SMB_RETRY_DELAY, DEFAULT_SMB_RETRY_DELAY, node, 
                getFrameworkProject(), getFramework()); 
        }
        catch(Exception e) {
        	throw new FileCopierException("SMB file copy failed.", Reason.CopyFileFailed, e);
        }
        /**
         * Copy the file over
         */
        context.getExecutionListener().log(3,"copying file: '" + localTempfile.getAbsolutePath()
                + "' to: '" + node.getNodename() + ":" + remotefile + "'");

        // Parse the remote file path
        String shareName = null;
        String path = "";
        for(int i=0;i<remotefile.split("\\\\").length;i++) {
        	String element = remotefile.split("\\\\")[i];
        	if(i==0) {
        		// First element of path is a Windows drive letter
        		if(element.length() != 2) {
        			throw new IllegalArgumentException("Invalid path root length."); 
        		}
        		if(!element.endsWith(":")) {
        			throw new IllegalArgumentException("Invalid path syntax."); 
        		}
        		char c = element.charAt(0);
        		if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')){
        			throw new IllegalArgumentException("Invalid path drive letter.");         			
        		}
        		shareName = Character.toString(c) + "$";
        	}
        	else if(i!=(remotefile.split("\\\\").length-1)) {
        		// Successive elements of path are directories
        		path += element + "\\";
        	}
        	else {
        		// Last element of path is the filename
        		path += element;
        	}
        		
        }

        
        
        SMBClient client = new SMBClient();
        Connection connection = null;
        Session session = null;
        for(int retry=0;retry<=retryMax;retry++) {        
        try {
        	connection = client.connect(node.getHostname());
        	ConnectionInfo info = connection.getConnectionInfo();
            context.getExecutionListener().log(3,logprompt + "ConnectionInfo.ServerName = " + info.getServerName());
            context.getExecutionListener().log(3,logprompt + "ConnectionInfo.NegotiatedProtocol = " + info.getNegotiatedProtocol().getDialect().toString());
        	
            AuthenticationContext ac = new AuthenticationContext(username, password.toCharArray(), domain);
            session = connection.authenticate(ac);

            // Connect to Share
       		DiskShare share = null;
            try {
            	share = (DiskShare) session.connectShare(shareName);

            	// Parse the remote path and create missing directories
            	String testPath = "";
                for(int i=0;i<path.split("\\\\").length-1;i++) {
                	String element = path.split("\\\\")[i];
               		testPath += element;
               		if(!share.folderExists(testPath)) {
               			// Directory does not exist, create
               			share.mkdir(testPath);
               		}
               		testPath += "\\";                		                	
                }

            	SmbFiles.copy(localTempfile, share, path, true);
                // We completed without any exceptions, make sure we don't repeat  
                retry=retryMax;

            }
            catch(Exception e) {
            	throw e;
            }
            finally {
        		try {
					share.close();
				} catch (IOException e) {
				}
            }

        }   
        catch(com.hierynomus.protocol.transport.TransportException e) {
        	// Retry operation
        	if(retry==retryMax) {
                throw new FileCopierException("SMB file copy failed.", Reason.CopyFileFailed, e);        	        	        		
        	}
        	else {
                context.getExecutionListener().log(Constants.WARN_LEVEL,logprompt + "Retrying SMB file copy.");
        		try {
					Thread.sleep(retryDelay * 1000);
				} catch (InterruptedException e1) {
				}
        	}
        		
        }
        
        catch(Exception e) {
            throw new FileCopierException("SMB file copy failed.", Reason.CopyFileFailed, e);        	
        }
        finally {
        	if(session != null) {
        		try {
					session.close();
				} catch (IOException e) {
				}
        	}        	
        	if(connection != null) {
        		try {
					connection.close();
				} catch (IOException e) {
				}
        	}        	
        }
        
        }
              
        
        if (!localTempfile.delete()) {
            context.getExecutionListener().log(Constants.WARN_LEVEL,
                    "Unable to remove local temp file: " + localTempfile.getAbsolutePath());
        }
        return remotefile;
        
    }

    public String copyFileStream(ExecutionContext context, InputStream input, INodeEntry node,
            String destination) throws FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFileStream(ExecutionContext context, InputStream input, INodeEntry node, String destination): " + destination);
        return copyFile(context, null, input, null, node, destination);
    }

    public String copyFile(ExecutionContext context, File file, INodeEntry node,
            String destination) throws FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFile(ExecutionContext context, File file, INodeEntry node, String destination): " + destination);
        return copyFile(context, file, null, null, node, destination);
    }

    public String copyScriptContent(ExecutionContext context, String script, INodeEntry node,
            String destination) throws FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyScriptContent(ExecutionContext context, String script, INodeEntry node, String destination)" + destination);
        return copyFile(context, null, null, script, node, destination);
    }
}
