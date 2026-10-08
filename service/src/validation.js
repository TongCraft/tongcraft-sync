import { gunzipSync } from "node:zlib";

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
export function check(ok, status, message) {
  if (!ok) throw new HttpError(status, message);
}
export function uuid(value) {
  check(
    typeof value === "string" &&
      /^[0-9a-f]{32}$/i.test(value.replaceAll("-", "")),
    400,
    "Invalid UUID",
  );
  return value.replaceAll("-", "").toLowerCase();
}
export function text(value, max = 128) {
  check(
    typeof value === "string" &&
      value.trim().length > 0 &&
      value.length <= max &&
      !/[\x00-\x1f]/.test(value),
    400,
    "Invalid text",
  );
  return value.trim();
}
export function position(value) {
  check(
    Array.isArray(value) &&
      value.length === 3 &&
      value.every((n) => Number.isSafeInteger(n) && Math.abs(n) <= 30000000),
    400,
    "Invalid position",
  );
  return value;
}
const rotations = [
  "NONE",
  "CLOCKWISE_90",
  "CLOCKWISE_180",
  "COUNTERCLOCKWISE_90",
];
const mirrors = ["NONE", "LEFT_RIGHT", "FRONT_BACK"];
function transform(input, sub = false) {
  check(input && typeof input === "object", 400, "Invalid transform");
  check(
    rotations.includes(input.rotation) && mirrors.includes(input.mirror),
    400,
    "Invalid rotation or mirror",
  );
  return {
    [sub ? "pos" : "origin"]: position(input[sub ? "pos" : "origin"]),
    rotation: input.rotation,
    mirror: input.mirror,
    ...(sub
      ? {
          enabled: input.enabled !== false,
          rendering_enabled: input.rendering_enabled !== false,
          ignore_entities: input.ignore_entities === true,
        }
      : {}),
  };
}
export function placement(input) {
  const name = text(input.name);
  check(
    typeof input.hash === "string" && /^[a-f0-9]{64}$/.test(input.hash),
    400,
    "Invalid schematic hash",
  );
  check(
    typeof input.dimension === "string" &&
      /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(input.dimension) &&
      input.dimension.length <= 128,
    400,
    "Invalid dimension",
  );
  check(
    Array.isArray(input.placements) && input.placements.length <= 1024,
    400,
    "Invalid subregions",
  );
  const names = new Set();
  const placements = input.placements.map((region) => {
    const name = text(region.name, 256);
    check(!names.has(name), 400, "Duplicate subregion");
    names.add(name);
    return { name, placement: transform(region.placement, true) };
  });
  return {
    name,
    hash: input.hash,
    dimension: input.dimension,
    ...transform(input),
    placements,
    ignore_entities: input.ignore_entities === true,
  };
}

// Bounded NBT reader: arrays are skipped rather than allocated. Only header/region geometry is retained.
export function validateSchematic(
  compressed,
  maxOutputLength = 128 * 1024 * 1024,
) {
  let buf;
  try {
    buf = gunzipSync(compressed, { maxOutputLength });
  } catch {
    throw new HttpError(400, "Invalid or oversized gzip schematic");
  }
  let offset = 0,
    nodes = 0;
  function take(n) {
    check(n >= 0 && offset + n <= buf.length, 400, "Truncated NBT");
    const start = offset;
    offset += n;
    return start;
  }
  function byte() {
    return buf.readUInt8(take(1));
  }
  function int() {
    return buf.readInt32BE(take(4));
  }
  function string() {
    const len = buf.readUInt16BE(take(2));
    return buf.toString("utf8", take(len), offset);
  }
  function payload(type, depth) {
    check(
      depth <= 32 && ++nodes <= 2000000,
      400,
      "NBT complexity limit exceeded",
    );
    switch (type) {
      case 1:
        return buf.readInt8(take(1));
      case 2:
        return buf.readInt16BE(take(2));
      case 3:
        return int();
      case 4:
        take(8);
        return null;
      case 5:
        take(4);
        return null;
      case 6:
        take(8);
        return null;
      case 7:
      case 11:
      case 12: {
        const n = int();
        check(n >= 0, 400, "Negative NBT array");
        take(n * { 7: 1, 11: 4, 12: 8 }[type]);
        return { type, length: n };
      }
      case 8:
        return string();
      case 9: {
        const subtype = byte(),
          n = int();
        check(
          n >= 0 && n <= 1000000 && (subtype !== 0 || n === 0),
          400,
          "Invalid NBT list",
        );
        for (let i = 0; i < n; i++) payload(subtype, depth + 1);
        return { listType: subtype, length: n };
      }
      case 10: {
        const obj = Object.create(null);
        for (;;) {
          const t = byte();
          if (t === 0) break;
          const key = string();
          obj[key] = payload(t, depth + 1);
        }
        return obj;
      }
      default:
        throw new HttpError(400, "Invalid NBT tag");
    }
  }
  check(byte() === 10, 400, "NBT root must be a compound");
  string();
  const root = payload(10, 0);
  check(
    offset === buf.length &&
      Number.isInteger(root.Version) &&
      root.Version >= 4 &&
      root.Version <= 7,
    400,
    "Unsupported litematic version",
  );
  check(
    root.Regions && typeof root.Regions === "object",
    400,
    "Missing schematic regions",
  );
  const regions = Object.entries(root.Regions);
  check(
    regions.length > 0 && regions.length <= 1024,
    400,
    "Invalid region count",
  );
  let volume = 0;
  for (const [, region] of regions) {
    check(
      region &&
        region.Size &&
        region.Position &&
        region.BlockStates?.type === 12 &&
        region.BlockStatePalette?.listType === 10 &&
        region.BlockStatePalette.length > 0 &&
        region.BlockStatePalette.length <= 65536,
      400,
      "Invalid litematic region",
    );
    const size = ["x", "y", "z"].map((k) => region.Size[k]);
    check(
      size.every((n) => Number.isInteger(n) && n !== 0 && Math.abs(n) <= 4096),
      400,
      "Region size limit exceeded",
    );
    volume += size.reduce((a, n) => a * Math.abs(n), 1);
    const blocks = size.reduce((a, n) => a * Math.abs(n), 1),
      bits = Math.max(2, Math.ceil(Math.log2(region.BlockStatePalette.length)));
    check(
      region.BlockStates.length === Math.ceil((blocks * bits) / 64),
      400,
      "Invalid block state array length",
    );
    position(["x", "y", "z"].map((k) => region.Position[k]));
  }
  check(volume <= 32000000, 400, "Schematic volume exceeds 32 million blocks");
  return { regions: regions.map(([name]) => name), volume };
}
