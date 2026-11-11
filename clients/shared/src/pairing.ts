/** The part of a `fetch` response the pairing reads. */
export interface PairResponse {
  status: number;
}

/** Sends `POST /pair`; tests pass a fake one. */
export type PairFetch = (url: string, init: RequestInit) => Promise<PairResponse>;

/** Whether the service paired this device; on failure, the text to show the owner. */
export type PairingResult = { paired: true } | { paired: false; error: string };

const browserFetch: PairFetch = (url, init) => fetch(url, init);

/**
 * Exchanges a pairing code for a device key: `POST /pair` to the service that served the page. On 204
 * the service has set the key cookie; 401 means a wrong or expired code, any other answer a refusal.
 */
export async function pairDevice(
  code: string,
  fetchPair: PairFetch = browserFetch,
): Promise<PairingResult> {
  let status: number;
  try {
    const response = await fetchPair("/pair", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
      credentials: "include",
      cache: "no-store",
    });
    status = response.status;
  } catch {
    return { paired: false, error: "Could not reach the service" };
  }
  if (status === 204) return { paired: true };
  if (status === 401) return { paired: false, error: "Invalid or expired code" };
  return { paired: false, error: "Pairing was refused by the service" };
}

const PAIRING_LINK = "#pair=";

/**
 * The code of a pairing link `#pair=<code>`, read from `location.hash`; an empty string when the
 * link carries something other than six digits, `undefined` when the fragment is not a pairing link.
 */
export function pairingCodeFromHash(hash: string): string | undefined {
  if (!hash.startsWith(PAIRING_LINK)) return undefined;
  const code = hash.slice(PAIRING_LINK.length);
  return /^\d{6}$/.test(code) ? code : "";
}
