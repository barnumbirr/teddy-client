package fi.iki.aeirola.teddyclientlib;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import fi.iki.aeirola.teddyclient.fragments.SettingsFragment;
import fi.iki.aeirola.teddyclientlib.models.Line;
import fi.iki.aeirola.teddyclientlib.models.Window;
import fi.iki.aeirola.teddyclientlib.models.request.InfoRequest;
import fi.iki.aeirola.teddyclientlib.models.request.InputRequest;
import fi.iki.aeirola.teddyclientlib.models.request.ItemRequest;
import fi.iki.aeirola.teddyclientlib.models.request.LineRequest;
import fi.iki.aeirola.teddyclientlib.models.request.Request;
import fi.iki.aeirola.teddyclientlib.models.request.WindowRequest;
import fi.iki.aeirola.teddyclientlib.models.response.LineResponse;
import fi.iki.aeirola.teddyclientlib.models.response.Response;
import fi.iki.aeirola.teddyclientlib.utils.IdGenerator;
import fi.iki.aeirola.teddyclientlib.utils.TimeoutHandler;

/**
 * Created by aeirola on 14.10.2014.
 */
public class TeddyClient implements TimeoutHandler.TimeoutCallbackHandler, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = TeddyClient.class.getName();
    private static final int PING_TIMEOUT = 15000;
    private static final int REQUEST_TIMEOUT = 5000;
    private static final int IDLE_TIMEOUT = 60000;
    private static final int RECONNECT_INTERVAL = 1000;

    private static TeddyClient instance;
    private final Queue<Request> messageQueue = new ArrayDeque<>();
    private final Map<String, TeddyCallbackHandler> callbackHandlers = new HashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Set<Long> lineSyncs = new HashSet<>();
    private final TimeoutHandler pingTimeoutHandler;
    private final TimeoutHandler requestTimeoutHandler;
    private final TimeoutHandler idleTimeoutHandler;
    private TeddyConnectionHandler mConnectionHandler;
    private String url;
    private String password;
    private String cert;
    private String clientChallengeString;
    private String serverChallengeString;
    private State connectionState = State.DISCONNECTED;
    private Future<?> reconnectFuture;
    private Set<Long> pendingRequests = new HashSet<>();
    private IdGenerator idGenerator = new IdGenerator();

    protected TeddyClient(String url, String password, String certFingerprint) {
        this.url = url;
        this.password = password;
        this.cert = certFingerprint;

        this.pingTimeoutHandler = new TimeoutHandler(PING_TIMEOUT, this);
        this.requestTimeoutHandler = new TimeoutHandler(REQUEST_TIMEOUT, this);
        this.idleTimeoutHandler = new TimeoutHandler(IDLE_TIMEOUT, this);
    }

    protected TeddyClient(SharedPreferences sharedPref) {
        this(sharedPref.getString(SettingsFragment.KEY_PREF_URL, ""),
                sharedPref.getString(SettingsFragment.KEY_PREF_PASSWORD, ""),
                sharedPref.getString(SettingsFragment.KEY_PREF_CERT, ""));
        sharedPref.registerOnSharedPreferenceChangeListener(this);
    }

    protected TeddyClient(String url, String password) {
        this(url, password, null);
    }


    public static TeddyClient getInstance(Context context) {
        if (instance == null) {
            Log.d(TAG, "Creating new instance");
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            instance = new TeddyClient(pref);
        }

        return instance;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Log.i(TAG, "Updating preferences");
        instance.url = sharedPreferences.getString(SettingsFragment.KEY_PREF_URL, "");
        instance.password = sharedPreferences.getString(SettingsFragment.KEY_PREF_PASSWORD, "");
        instance.cert = sharedPreferences.getString(SettingsFragment.KEY_PREF_CERT, "");
        instance.disconnect();
    }

    protected void connect() {
        if (this.connectionState != State.DISCONNECTED) {
            return;
        }

        if (this.url == null) {
            Log.w(TAG, "Url not configured, not connecting");
            return;
        }

        Log.d(TAG, "Connecting to " + this.url);
        this.connectionState = State.CONNECTING;
        this.mConnectionHandler = new TeddyConnectionHandler(this.url, this.cert, this);
        this.mConnectionHandler.connect();
    }

    protected void reconnect() {
        Log.d(TAG, "Reconnecting to " + this.url);
        this.mConnectionHandler = new TeddyConnectionHandler(this.url, this.cert, this);
        this.mConnectionHandler.connect();
    }

    protected void onConnect() {
        switch (connectionState) {
            case CONNECTING:
                Log.d(TAG, "Connected");
                break;
            case RECONNECTING:
                Log.d(TAG, "Reconnected");
                break;
            default:
                Log.w(TAG, "Invalid state on connect " + connectionState);
        }

        pingTimeoutHandler.set();
        sendChallenge();
    }

    @Override
    public void onTimeout() {
        Log.d(TAG, "Connection ping timeout");
        this.mConnectionHandler.close();
        onDisconnect();
    }

    protected void disconnect() {
        Log.d(TAG, "Disconnecting");
        this.lineSyncs.clear();
        this.messageQueue.clear();
        if (mConnectionHandler != null) {
            this.mConnectionHandler.close();
        }
        onDisconnect();
    }

    protected void onDisconnect() {
        Log.d(TAG, "Disconnected");

        pingTimeoutHandler.cancel();
        idleTimeoutHandler.cancel();
        requestTimeoutHandler.cancel();

        // Clear pending requests
        pendingRequests.clear();
        notifyPendingRequests();

        if (this.connectionState == State.CONNECTING || this.connectionState == State.RECONNECTING) {
            if (reconnectFuture != null && !reconnectFuture.isDone()) {
                return;
            }
            // Retry connection in a moment
            reconnectFuture = worker.schedule(new Runnable() {
                @Override
                public void run() {
                    reconnect();
                }
            }, RECONNECT_INTERVAL, TimeUnit.MILLISECONDS);
            return;
        }

        if (!this.lineSyncs.isEmpty() || !this.messageQueue.isEmpty()) {
            // Reconnect if there is something going on
            this.connectionState = State.RECONNECTING;
            this.reconnect();
        }

        this.connectionState = State.DISCONNECTED;
        for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
            callbackHandler.onDisconnect();
        }
    }

    void onMessage(Response response) {
        requestTimeoutHandler.cancel();
        pendingRequests.remove(response.id);
        notifyPendingRequests();

        if (response.challenge != null) {
            this.onChallenge((response.challenge));
        }

        if (response.login != null) {
            if (response.login) {
                this.onLogin();
            } else {
                Log.i(TAG, "Login failed");
            }
        }

        if (response.info != null) {
            if (response.info.version != null) {
                this.onVersion((response.info.version));
            }
        }

        if (response.window != null) {
            this.onWindowList(response.window.toList(response.item));
        }

        if (response.line != null) {
            this.onLineList(response.line.toList());
        }

        if (response.lineAdded != null) {
            this.onNewLines(LineResponse.toList(response.lineAdded));
        }
    }

    public void sendChallenge() {
        // Generate random string for client challenge
        this.clientChallengeString = this.generateClientChallenge();
        Log.d(TAG, "Sending client challenge: " + this.clientChallengeString);
        Request request = new Request();
        request.challenge = this.clientChallengeString;
        this.send(request, false);
    }

    protected void onChallenge(String challenge) {
        Log.d(TAG, "Received server challenge: " + challenge);
        this.serverChallengeString = challenge;
        this.sendLogin();
    }

    public void sendLogin() {
        String loginToken = this.getLoginToken();
        Log.d(TAG, "Sending login token: " + loginToken);
        Request request = new Request();
        request.login = loginToken;
        this.send(request, false);
    }

    protected void onLogin() {
        Log.i(TAG, "Logged in!");

        // Update state
        switch (connectionState) {
            case CONNECTING:
                connectionState = State.CONNECTED;
                sendQueuedMessages();

                for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
                    callbackHandler.onConnect();
                }
                break;
            case RECONNECTING:
                connectionState = State.CONNECTED;
                sendQueuedMessages();

                for (long viewId : lineSyncs) {
                    this.subscribeLines(viewId);
                }

                for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
                    callbackHandler.onReconnect();
                }
                break;
            default:
                Log.w(TAG, "Invalid connection state during login" + connectionState);
        }
    }

    private void sendQueuedMessages() {
        Request message;
        while ((message = this.messageQueue.poll()) != null) {
            this.send(message);
        }
    }

    public void requestVersion() {
        Log.d(TAG, "Requesting version");
        Request request = new Request();
        request.info = new InfoRequest("version");
        this.send(request);
    }

    protected void onVersion(String version) {
        Log.d(TAG, "Version received: " + version);
        for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
            callbackHandler.onVersion(version);
        }
    }

    protected void onPing() {
        Log.d(TAG, "Ping");
        pingTimeoutHandler.reset();
    }

    public void requestWindowList() {
        Log.d(TAG, "Requesting window list");
        Request request = new Request();
        request.window = new WindowRequest();
        request.window.get = new WindowRequest.Get();
        request.item = new ItemRequest();
        this.send(request);
    }

    public void requestWindow(long windowId) {
        Log.d(TAG, "Requesting window " + windowId);
        // TODO: Get only single window
        Request request = new Request();
        request.window = new WindowRequest();
        request.window.get = new WindowRequest.Get();
        request.item = new ItemRequest();
        this.send(request);
    }

    protected void onWindowList(List<Window> windowList) {
        Log.d(TAG, "Received window list");
        for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
            callbackHandler.onWindowList(windowList);
        }
    }

    public void resetWindowActivity(long windowId) {
        Log.d(TAG, "Reseting window activity for " + windowId);
        Request request = new Request();
        request.window = new WindowRequest();
        request.window.dehilight = new ArrayList<>();
        request.window.dehilight.add(windowId);
        this.send(request);
    }

    public void requestLineList(long viewId, int count) {
        LineRequest.Get lineRequest = new LineRequest.Get();
        lineRequest.count = count;
        this.requestLineList(viewId, lineRequest);
    }

    public void requestLineList(long viewId, LineRequest.Get lineRequest) {
        Request request = new Request();
        request.line = new LineRequest();
        request.line.get = new HashMap<>();
        request.line.get.put(viewId, lineRequest);
        this.send(request);
    }

    protected void onLineList(List<Line> lineList) {
        for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
            callbackHandler.onLineList(lineList);
        }
    }

    protected void onNewLines(List<Line> lineList) {
        for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
            callbackHandler.onNewLines(lineList);
        }
    }

    public void sendInput(long windowId, String message) {
        Request request = new Request();
        request.input = new InputRequest(windowId, message);
        this.send(request);
    }

    public void subscribeLines(long viewId) {
        this.lineSyncs.add(viewId);
        idleTimeoutHandler.disable();

        Request request = new Request();
        request.line = new LineRequest();
        request.line.sub_add = new LineRequest.Sub();
        request.line.sub_add.add = new LineRequest.Subscription();
        request.line.sub_add.add.view.add(viewId);

        this.send(request);
    }

    public void unsubscribeLines(long viewId) {
        Request request = new Request();
        request.line = new LineRequest();
        request.line.sub_rm = new LineRequest.Sub();
        request.line.sub_rm.add = new LineRequest.Subscription();
        request.line.sub_rm.add.view.add(viewId);
        this.send(request);

        idleTimeoutHandler.enable();
        this.lineSyncs.remove(viewId);
    }

    public void registerCallbackHandler(TeddyCallbackHandler callbackHandler, String handlerKey) {
        this.callbackHandlers.put(handlerKey, callbackHandler);
    }

    public void removeCallBackHandler(String handlerKey) {
        this.callbackHandlers.remove(handlerKey);
    }

    protected void send(Request request) {
        this.send(request, true);
    }

    protected void send(Request request, boolean waitForLogin) {
        if (request.expectResponse()) {
            if (request.id == null) {
                request.id = idGenerator.get();
            }
            pendingRequests.add(request.id);
            notifyPendingRequests();
        }

        idleTimeoutHandler.reset();

        switch (this.connectionState) {
            case DISCONNECTED:
                this.connect();
                this.messageQueue.add(request);
                break;
            case CONNECTING:
            case RECONNECTING:
                if (waitForLogin) {
                    // Queue messages to be sent when logged in
                    this.messageQueue.add(request);
                } else {
                    sendWithoutQueue(request);
                }
                break;
            case CONNECTED:
                sendWithoutQueue(request);
                break;
            default:
                Log.w(TAG, "Unknown state while sending: " + this.connectionState);
        }
    }

    private void sendWithoutQueue(Request request) {
        if (request.expectResponse()) {
            requestTimeoutHandler.set();
        }
        this.mConnectionHandler.send(request);
    }

    private void notifyPendingRequests() {
        Log.v(TAG, "Pending requests " + this.pendingRequests.size());
        if (this.pendingRequests.isEmpty()) {
            for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
                callbackHandler.onNoPendingRequests();
            }
        } else {
            for (TeddyCallbackHandler callbackHandler : this.callbackHandlers.values()) {
                callbackHandler.onPendingRequests();
            }
        }
    }

    private String generateClientChallenge() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("HmacSHA256");
            SecretKey clientChallengeKey = keyGenerator.generateKey();
            return Base64.encodeToString(clientChallengeKey.getEncoded(), Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Login challenge generation failed", e);
            return null;
        }
    }

    private String getLoginToken() {
        try {
            // Create secret key from
            String secret = this.serverChallengeString + this.clientChallengeString;
            SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");

            // Create encoder instance
            Mac hmacSHA256 = Mac.getInstance("HmacSHA256");
            hmacSHA256.init(secret_key);

            // Create token
            byte[] token = hmacSHA256.doFinal(this.password.getBytes());

            // Base64 encode the token
            return Base64.encodeToString(token, Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Login failed", e);
            return null;
        } catch (InvalidKeyException e) {
            Log.e(TAG, "Login failed", e);
            return null;
        }
    }
}

