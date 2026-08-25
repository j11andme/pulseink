import { describe, expect, it } from "vitest";
import { formatDate, formatDateTime, TIME_ZONE } from "./date";

describe("date formatting", () => {
  it("always renders instants in Asia/Shanghai", () => {
    expect(TIME_ZONE).toBe("Asia/Shanghai");
    expect(formatDateTime("2026-08-04T12:00:00Z")).toBe("2026-08-04 20:00:00");
    expect(formatDateTime("2026-01-01T00:30:00Z")).toBe("2026-01-01 08:30:00");
  });

  it("formats a date-only string in Asia/Shanghai", () => {
    expect(formatDate("2026-08-04T12:00:00Z")).toBe("2026-08-04");
  });

  it("returns a stable placeholder for missing values", () => {
    expect(formatDateTime(null)).toBe("-");
    expect(formatDateTime(undefined)).toBe("-");
    expect(formatDateTime("")).toBe("-");
    expect(formatDate(null)).toBe("-");
  });

  it("falls back to the raw value for unparsable input", () => {
    expect(formatDateTime("not-a-date")).toBe("not-a-date");
  });
});
