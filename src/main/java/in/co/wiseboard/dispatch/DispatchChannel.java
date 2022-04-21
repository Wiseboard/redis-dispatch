package in.co.wiseboard.dispatch;

public interface DispatchChannel {
    void onDispatchMessage(String channelName, byte[] message);
    void onDispatchSubscribed(String channelName);
    void onDispatchUnsubscribed(String channelName);
}
