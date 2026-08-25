export const TIME_ZONE = "Asia/Shanghai";

function dateParts(value: string): Intl.DateTimeFormatPart[] | null {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).formatToParts(date);
}

function part(parts: Intl.DateTimeFormatPart[], type: string): string {
  return parts.find((item) => item.type === type)?.value ?? "";
}

/**
 * Formats an ISO-8601 instant as `YYYY-MM-DD HH:mm:ss` in Asia/Shanghai. Every product page must
 * use this helper instead of a local per-view formatter.
 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  const parts = dateParts(value);
  if (!parts) {
    return value;
  }
  return `${part(parts, "year")}-${part(parts, "month")}-${part(parts, "day")} `
    + `${part(parts, "hour")}:${part(parts, "minute")}:${part(parts, "second")}`;
}

/** Formats an ISO-8601 instant as `YYYY-MM-DD` in Asia/Shanghai. */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  const parts = dateParts(value);
  if (!parts) {
    return value;
  }
  return `${part(parts, "year")}-${part(parts, "month")}-${part(parts, "day")}`;
}
