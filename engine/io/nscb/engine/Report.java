package io.nscb.engine;

import java.util.ArrayList;
import java.util.List;

public final class Report {
    public String fileName = "";
    public String container = "";
    public long fileSize;
    public String titleId = "";
    public long version = -1;
    public String titleType = "";
    public long requiredSystemVersion = -1;
    public String nacpName = "";
    public String nacpPublisher = "";
    public String displayVersion = "";
    public boolean hasTicket;
    public boolean titleKeyAvailable;
    public String note = "";
    public final List<String> contents = new ArrayList<>();
    public byte[] icon;

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(fileName).append('\n');
        sb.append("Container: ").append(container).append('\n');
        sb.append("Size: ").append(Util.size(fileSize)).append(" (").append(fileSize).append(" bytes)\n");
        if (!titleId.isEmpty()) sb.append("Title ID: ").append(titleId).append('\n');
        if (version >= 0) {
            sb.append("Version: ").append(version).append(" (v").append(version).append(" / 0x")
                    .append(Long.toHexString(version).toUpperCase()).append(")\n");
        }
        if (!titleType.isEmpty()) sb.append("Title type: ").append(titleType).append('\n');
        if (requiredSystemVersion >= 0) {
            sb.append("Required system version: ").append(requiredSystemVersion).append(" (0x")
                    .append(Long.toHexString(requiredSystemVersion).toUpperCase()).append(")\n");
        }
        if (!nacpName.isEmpty()) sb.append("Name: ").append(nacpName).append('\n');
        if (!nacpPublisher.isEmpty()) sb.append("Publisher: ").append(nacpPublisher).append('\n');
        if (!displayVersion.isEmpty()) sb.append("Display version: ").append(displayVersion).append('\n');
        sb.append("Ticket: ").append(hasTicket ? "present" : "absent").append('\n');
        sb.append("Title key: ").append(titleKeyAvailable ? "available" : "unavailable").append('\n');
        if (!note.isEmpty()) sb.append("Note: ").append(note).append('\n');
        if (!contents.isEmpty()) {
            sb.append("\nContents (").append(contents.size()).append("):\n");
            for (String c : contents) sb.append("  ").append(c).append('\n');
        }
        return sb.toString();
    }
}
