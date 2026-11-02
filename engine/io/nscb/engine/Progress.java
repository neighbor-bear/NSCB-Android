package io.nscb.engine;

public interface Progress {
    void onProgress(String message, long done, long total);

    void onLog(String message);

    Progress NONE = new Progress() {
        @Override
        public void onProgress(String message, long done, long total) {
        }

        @Override
        public void onLog(String message) {
        }
    };
}
