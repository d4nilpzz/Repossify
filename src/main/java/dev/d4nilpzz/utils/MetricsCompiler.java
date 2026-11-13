package dev.d4nilpzz.utils;

import com.sun.management.OperatingSystemMXBean;
import dev.d4nilpzz.Repossify;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Locale;

public final class MetricsCompiler {

    private MetricsCompiler() {
    }

    public static String getMetricsJson() {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        double cpuLoad = os.getCpuLoad();
        // getCpuLoad returns a negative value until the JVM has a sample to compare against.
        int cpu = cpuLoad < 0 ? 0 : (int) Math.round(cpuLoad * 100);

        double ramTotal = os.getTotalMemorySize() / (1024.0 * 1024 * 1024);
        double ramFree = os.getFreeMemorySize() / (1024.0 * 1024 * 1024);
        double ramUsed = ramTotal - ramFree;

        // The volume holding the repositories, not the root of the filesystem tree, which on
        // Windows is not where the data lives.
        File disk = Repossify.WORKING_DIR.toFile();
        double diskTotal = disk.getTotalSpace() / (1024.0 * 1024 * 1024);
        double diskFree = disk.getUsableSpace() / (1024.0 * 1024 * 1024);
        double diskUsed = diskTotal - diskFree;

        // Locale.ROOT, not the default one: on a host whose locale uses comma decimals
        // %.2f renders 20,38 and the payload stops being JSON. The dashboard parses this
        // inside a try/catch, so it drops the frame and shows nothing at all.
        return String.format(Locale.ROOT, """
                {
                  "cpu": { "used": %d, "max": 100 },
                  "ram": { "used": %.2f, "max": %.2f },
                  "storage": { "used": %.2f, "max": %.2f }
                }
                """, cpu, ramUsed, ramTotal, diskUsed, diskTotal);
    }
}
