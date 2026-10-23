package io.github.yaulun.javacpp;

/** Device identity and current available/total memory, measured in bytes. */
public record BackendDevice(
    int index, String name, String description, DeviceType type, long freeBytes, long totalBytes) {
  /** Returns whether the device supports GPU offload. */
  public boolean isGpu() {
    return type == DeviceType.GPU || type == DeviceType.IGPU;
  }
}
