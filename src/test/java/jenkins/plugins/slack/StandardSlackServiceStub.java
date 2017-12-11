package jenkins.plugins.slack;

public class StandardSlackServiceStub extends StandardSlackService {

    private HttpClientStub httpClientStub;

    public StandardSlackServiceStub(String teamDomain, String token, String roomId, TaskListener listener) {
        super(teamDomain, token, roomId, listener);
    }

    @Override
    public HttpClientStub getHttpClient() {
        return httpClientStub;
    }

    public void setHttpClient(HttpClientStub httpClientStub) {
        this.httpClientStub = httpClientStub;
    }
}
