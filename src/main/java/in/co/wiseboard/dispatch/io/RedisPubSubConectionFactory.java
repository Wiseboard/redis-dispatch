package in.co.wiseboard.dispatch.io;

import in.co.wiseboard.dispatch.redis.PubSubConnection;

public interface RedisPubSubConectionFactory {

    PubSubConnection connect();
    
}
