import { describe, expect, it } from "vitest";
import { pairDevice, pairingCodeFromHash, type PairResponse } from "./pairing";

/** Plays `POST /pair`: records the request and gives one answer, a status or a network failure. */
function fakePair(answer: number | "offline") {
  const calls: Array<{ url: string; init: RequestInit }> = [];
  const fetch = (url: string, init: RequestInit): Promise<PairResponse> => {
    calls.push({ url, init });
    return answer === "offline"
      ? Promise.reject(new TypeError("Failed to fetch"))
      : Promise.resolve({ status: answer });
  };
  return { calls, fetch };
}

describe("pairDevice", () => {
  it("posts the code as JSON to the same origin, with cookies and without the cache", async () => {
    const service = fakePair(204);

    await pairDevice("123456", service.fetch);

    expect(service.calls).toEqual([
      {
        url: "/pair",
        init: {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code: "123456" }),
          credentials: "include",
          cache: "no-store",
        },
      },
    ]);
  });

  it("succeeds on 204", async () => {
    expect(await pairDevice("123456", fakePair(204).fetch)).toEqual({ paired: true });
  });

  it("says the code is invalid or expired on 401", async () => {
    expect(await pairDevice("000000", fakePair(401).fetch)).toEqual({
      paired: false,
      error: "Invalid or expired code",
    });
  });

  it.each([403, 400, 500, 200])("says the service refused on %i", async (status) => {
    expect(await pairDevice("123456", fakePair(status).fetch)).toEqual({
      paired: false,
      error: "Pairing was refused by the service",
    });
  });

  it("says the service cannot be reached when the request fails", async () => {
    expect(await pairDevice("123456", fakePair("offline").fetch)).toEqual({
      paired: false,
      error: "Could not reach the service",
    });
  });
});

describe("pairingCodeFromHash", () => {
  it("reads a six-digit code from a pairing link", () => {
    expect(pairingCodeFromHash("#pair=123456")).toBe("123456");
  });

  it("gives an empty code for a pairing link whose code is not six digits", () => {
    expect(pairingCodeFromHash("#pair=")).toBe("");
    expect(pairingCodeFromHash("#pair=12345")).toBe("");
    expect(pairingCodeFromHash("#pair=1234567")).toBe("");
    expect(pairingCodeFromHash("#pair=<b>x</b>")).toBe("");
  });

  it("gives nothing when the fragment is not a pairing link", () => {
    expect(pairingCodeFromHash("")).toBeUndefined();
    expect(pairingCodeFromHash("#")).toBeUndefined();
    expect(pairingCodeFromHash("#section")).toBeUndefined();
    expect(pairingCodeFromHash("#x&pair=123456")).toBeUndefined();
  });
});
