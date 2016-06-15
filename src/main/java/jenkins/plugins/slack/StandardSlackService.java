package jenkins.plugins.slack;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.PostMethod;

import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.List;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import hudson.ProxyConfiguration;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;

public class StandardSlackService implements SlackService {

    private static final Logger logger = Logger.getLogger(StandardSlackService.class.getName());

    private String host = "slack.com";
    private String teamDomain;
    private String token;
    private String[] roomIds;
    private String roomIdsStr;
    private TaskListener listener;

    public StandardSlackService(String teamDomain, String token, String roomId, TaskListener listener) {
        super();
        this.teamDomain = teamDomain;
        this.token = token;
        this.roomIds = roomId.split("[,; ]+");
        this.roomIdsStr = roomId;
        this.listener = listener;
    }

    public boolean publish(String message) {
        return publish(message, "warning");
    }

    public boolean publish(String message, String color) {
        return publish(message, color, null, null);
    }

    public boolean publish(String message, String color, List<File> filesToUpload, String uploadFilesUserToken) {
        boolean result = true;
        for (String roomId : roomIds) {
            String url = "https://" + teamDomain + "." + host + "/services/hooks/jenkins-ci?token=" + token;
            logger.info("Posting: to " + roomId + " on " + teamDomain + " using " + url +": " + message + " " + color);
            HttpClient client = getHttpClient();
            PostMethod post = new PostMethod(url);
            JSONObject json = new JSONObject();

            try {
                JSONObject field = new JSONObject();
                field.put("short", false);
                field.put("value", message);

                JSONArray fields = new JSONArray();
                fields.put(field);

                JSONObject attachment = new JSONObject();
                attachment.put("fallback", message);
                attachment.put("color", color);
                attachment.put("fields", fields);
                JSONArray mrkdwn = new JSONArray();
                mrkdwn.put("pretext");
                mrkdwn.put("text");
                mrkdwn.put("fields");
                attachment.put("mrkdwn_in", mrkdwn);
                JSONArray attachments = new JSONArray();
                attachments.put(attachment);

                json.put("channel", roomId);
                json.put("attachments", attachments);

                post.addParameter("payload", json.toString());
                post.getParams().setContentCharset("UTF-8");
                int responseCode = client.executeMethod(post);
                String response = post.getResponseBodyAsString();
                if(responseCode != HttpStatus.SC_OK) {
                    logger.log(Level.WARNING, "Slack post may have failed. Response: " + response);
                    result = false;
                }
                else {
                    logger.info("Posting succeeded");
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error posting to Slack", e);
                result = false;
            } finally {
                post.releaseConnection();
            }
        }

        if (result) {
            logger.info("Posting success, checking for files to upload: " + filesToUpload);
            if (filesToUpload != null && filesToUpload.size() > 0) {
                uploadFiles(filesToUpload, uploadFilesUserToken);
            }
        } else {
            logger.info("Posting failed, not checking for files to upload");
        }
        return result;
    }


    public boolean uploadFiles(List<File> filesToUpload, String uploadFilesUserToken) {
        boolean result = true;

        if (filesToUpload == null || filesToUpload.size() == 0) {
            logger.info("uploadFiles: no filesToUpload");
            if (listener != null) listener.getLogger().println("Slack file uploader: no files to upload");
            return false;
        }

        if (listener != null) listener.getLogger().println("Slack file uploader: uploading " +  filesToUpload.size() + " files to channels '" + roomIdsStr + "':");

        for (File file : filesToUpload) {
            String url = "https://" + host + "/api/files.upload";

            if (listener != null) listener.getLogger().println("Slack file uploader:  uploading '" + file + "' to channels '" + roomIdsStr + "', " + file.length() + " bytes...");
            HttpClient client = getHttpClient();
            PostMethod post = new PostMethod(url);

            try {
                Part[] parts = new Part[3];
                parts[0] = new StringPart("token", uploadFilesUserToken);
                parts[1] = new StringPart("channels", roomIdsStr);
                parts[2] = new FilePart("file", file);
                post.setRequestEntity(new MultipartRequestEntity(parts, post.getParams()));

                int responseCode = client.executeMethod(post);
                String response = post.getResponseBodyAsString();
                if(responseCode != HttpStatus.SC_OK) {
                    logger.log(Level.WARNING, "Slack upload may have failed. Response: " + response);
                    if (listener != null) listener.getLogger().println("Slack file uploader:  upload of file '" + file + "' errored with http response code " + responseCode + " and message: " + response);
                    result = false;
                } else {
                    JSONObject responseJson = new JSONObject(response);
                    boolean ok = responseJson.getBoolean("ok");
                    if (ok) {
                        logger.info("Uploading file '" + file + "' succeeded. Response: " + response);
                        if (listener != null) listener.getLogger().println("Slack file uploader:  uploaded '" + file + "' ok.");
                    } else {
                        if (listener != null) listener.getLogger().println("Slack file uploader:  upload of file '" + file + "' errored, response message: " + response);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Exception uploading file '" + file + "' to Slack", e);
                if (listener != null) {
                    listener.getLogger().println("Exception uploading file '" + file + "' to Slack: " + e.toString());
                }
                result = false;
            } finally {
                post.releaseConnection();
            }
        }
        return result;
    }

    protected HttpClient getHttpClient() {
        HttpClient client = new HttpClient();
        if (Jenkins.getInstance() != null) {
            ProxyConfiguration proxy = Jenkins.getInstance().proxy;
            if (proxy != null) {
                client.getHostConfiguration().setProxy(proxy.name, proxy.port);
                String username = proxy.getUserName();
                String password = proxy.getPassword();
                // Consider it to be passed if username specified. Sufficient?
                if (username != null && !"".equals(username.trim())) {
                    logger.info("Using proxy authentication (user=" + username + ")");
                    // http://hc.apache.org/httpclient-3.x/authentication.html#Proxy_Authentication
                    // and
                    // http://svn.apache.org/viewvc/httpcomponents/oac.hc3x/trunk/src/examples/BasicAuthenticationExample.java?view=markup
                    client.getState().setProxyCredentials(AuthScope.ANY,
                        new UsernamePasswordCredentials(username, password));
                }
            }
        }
        return client;
    }

    void setHost(String host) {
        this.host = host;
    }
}
