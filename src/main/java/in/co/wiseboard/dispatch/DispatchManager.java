package in.co.wiseboard.dispatch;

import in.co.wiseboard.dispatch.io.RedisPubSubConectionFactory;
import in.co.wiseboard.dispatch.redis.PubSubConnection;
import in.co.wiseboard.dispatch.redis.PubSubReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class DispatchManager extends Thread{
    private final Logger                               logger = LoggerFactory.getLogger(DispatchManager.class);
    private final Executor                           executor = Executors.newCachedThreadPool();
    private final Map<String , DispatchChannel> subscriptions = new ConcurrentHashMap<>();

    private final Optional<DispatchChannel>     deadLetterChannel;
    private final RedisPubSubConectionFactory   redisPubSubConectionFactory;

    private PubSubConnection                    pubSubConnection;
    private volatile boolean                    running;

    public DispatchManager(RedisPubSubConectionFactory redisPubSubConectionFactory, Optional<DispatchChannel> deadLetterChannel){
        this.deadLetterChannel = deadLetterChannel;
        this.redisPubSubConectionFactory = redisPubSubConectionFactory;
    }

    @Override
    public void start(){
        pubSubConnection = redisPubSubConectionFactory.connect();
        running = true;
        super.start();
    }

    public void shutdown(){
        this.running = false;
        this.pubSubConnection.close();
    }

    public synchronized void subscribe(String name, DispatchChannel dispatchChannel){
        Optional<DispatchChannel> previous = Optional.ofNullable(subscriptions.get(name));
        subscriptions.put(name, dispatchChannel);

        try{
            pubSubConnection.subscribe(name);
        } catch (IOException e) {
            logger.warn("Subscription error",e);
        }

        previous.ifPresent(channel -> dispatchUnsubscription(name, channel));
    }

    public synchronized void unsubscribe(String name, DispatchChannel dispatchChannel){
        Optional<DispatchChannel> subscription = Optional.ofNullable(subscriptions.get(name));

        if(subscription.isPresent() && subscription.get() == dispatchChannel){
            subscriptions.remove(name);

            try{
                pubSubConnection.unsubscribe(name);
            } catch (IOException e) {
                e.printStackTrace();
            }

            dispatchUnsubscription(name, subscription.get());
        }
    }

    public boolean hasSubscription(String name){
        return subscriptions.containsKey(name);
    }

    private void dispatchUnsubscription(final String name, final DispatchChannel dispatchChannel){
        executor.execute(() -> dispatchChannel.onDispatchUnsubscribed(name));
    }

    private void dispatchSubscription(final String name, final DispatchChannel dispatchChannel){
        executor.execute(() -> dispatchChannel.onDispatchSubscribed(name));
    }

    private void dispatchMessage(final String name, final DispatchChannel dispatchChannel, final byte[] message){
        executor.execute(() -> dispatchChannel.onDispatchMessage(name, message));
    }

    private void resubscribeAll(){
        new Thread(()->{
            synchronized (DispatchManager.this){
                try{
                    for(String name : subscriptions.keySet()){
                        pubSubConnection.subscribe(name);
                    }
                } catch (IOException e) {
                    logger.warn("****** RESUBSCRIPTION ERROR ******", e);
                }
            }
        }).start();
    }

    private void dispatchSubscribe(final PubSubReply reply){
        Optional<DispatchChannel> subscription = Optional.ofNullable(subscriptions.get(reply.getChannel()));

        if(subscription.isPresent()){
            dispatchSubscription(reply.getChannel(), subscription.get());
        }else{
            logger.info("Received subscribe event for non-existing channel: " + reply.getChannel());
        }
    }

    private void dispatchMessage(final PubSubReply reply){
        Optional<DispatchChannel> subscription = Optional.ofNullable(subscriptions.get(reply.getChannel()));

        if(subscription.isPresent()){
            dispatchMessage(reply.getChannel(), subscription.get(), reply.getContent().get());
        }else if (deadLetterChannel.isPresent()){
            dispatchMessage(reply.getChannel(), deadLetterChannel.get(), reply.getContent().get());
        }else{
            logger.warn("Received message for non-existing channel, with no dead letter handler: " + reply.getChannel());
        }
    }

    @Override
    public void run() {
        while(running){
            try{
                PubSubReply reply = pubSubConnection.read();

                switch(reply.getType()){
                    case UNSUBSCRIBE:                         break;
                    case SUBSCRIBE: dispatchSubscribe(reply); break;
                    case MESSAGE:   dispatchMessage(reply);   break;
                    default:        throw new AssertionError("Unknown pubsub reply type! " + reply.getType());
                }
            } catch (IOException e) {
                logger.warn("****** PubSub Connection Error ******");
                if(running){
                    this.pubSubConnection.close();
                    this.pubSubConnection = redisPubSubConectionFactory
                            .connect();
                    resubscribeAll();
                }
            }
        }

        logger.warn("DispatchManager Shutting Down...");
    }
}
