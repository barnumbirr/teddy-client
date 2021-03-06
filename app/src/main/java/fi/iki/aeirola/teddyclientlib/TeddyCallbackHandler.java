package fi.iki.aeirola.teddyclientlib;

import java.util.List;

import fi.iki.aeirola.teddyclientlib.models.Line;
import fi.iki.aeirola.teddyclientlib.models.Window;

public abstract class TeddyCallbackHandler {

    public void onConnect() {
    }

    public void onReconnect() {
    }

    public void onDisconnect() {
    }

    public void onVersion(String version) {
    }

    public void onWindowList(List<Window> windowList) {
    }

    public void onLineList(List<Line> lineList) {
    }

    public void onNewLines(List<Line> lineList) {
    }

    public void onPendingRequests() {
    }

    public void onNoPendingRequests() {
    }
}
