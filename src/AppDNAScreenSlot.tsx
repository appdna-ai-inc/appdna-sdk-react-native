// ADR-001 PlatformView carve-out (same as the Flutter ScreenSlot host): this facade hosts the native
// Fabric surface (AppdnaScreenSlotView) and manages only its measured height. It renders no UI of its
// own — no network, no storage, no business logic — so importing React to host the native view is
// allowed here and nowhere else. The marker must sit on the line directly above the import.
// thin-wrapper-ignore
import React, { useState, useCallback } from 'react';
import type { ViewStyle, StyleProp, NativeSyntheticEvent } from 'react-native';
import AppdnaScreenSlotView from './specs/AppdnaScreenSlotNativeComponent';

/**
 * The public React component for an inline, server-driven AppDNA screen slot.
 *
 * A slot renders whatever the console publishes for `name` — a native SwiftUI / Compose surface,
 * embedded through Fabric. See `specs/AppdnaScreenSlotNativeComponent.ts` for why height is driven
 * from JS and the two costs that entails.
 *
 * ```tsx
 * <AppDNAScreenSlot name="home_promo" minHeight={120} style={{ marginVertical: 16 }} />
 * ```
 */
export interface AppDNAScreenSlotProps {
  /** The console slot name to render. */
  name: string;

  /**
   * Height reserved before the first `onContentSizeChange` fires (W19). Without it the slot is 0pt on
   * the first frame and the layout visibly collapses, then expands one frame later. Pick a value near
   * the slot's expected height; the real measured height replaces it as soon as native reports one.
   * Defaults to 0 — a caller that wants no reserved space opts in explicitly.
   */
  minHeight?: number;

  /** Extra style for the slot container. `height` is managed internally and should not be set here. */
  style?: StyleProp<ViewStyle>;

  /** Notified with the measured content height whenever it changes (points/dp). */
  onContentSizeChange?: (size: { width: number; height: number }) => void;
}

/**
 * Last-known height per slot name, surviving unmount.
 *
 * `measuredHeight` is component state, so a REMOUNT (tab switch, list recycle, navigation back)
 * resets it to `undefined` and the slot drops to `minHeight` for a frame before native re-measures —
 * a visible layout shift on every remount, which is exactly what W19 forbids. AC-17 permits the
 * 0-height first frame at the MEASUREMENT layer; AC-37 forbids it reaching the screen.
 *
 * Keyed by slot name, because that is what determines the content: two mounts of `home_promo` render
 * the same server-driven surface and therefore the same height. It is a render hint only — the real
 * measurement still replaces it on the next `onContentSizeChange`, so a stale entry costs one
 * corrected frame, never a wrong final layout.
 */
const lastKnownHeight = new Map<string, number>();

/**
 * Bound the cache. The documented assumption is a fixed console slot set, but a host using per-item
 * names (`slot_${id}` for list rows) would grow it without limit over a long session. Evict
 * oldest-first (Map preserves insertion order) past the cap — a re-measure on the next mount refills
 * an evicted entry at the cost of one corrected frame, the same guarantee the cache already makes.
 */
const HEIGHT_CACHE_CAP = 128;
function rememberHeight(name: string, height: number): void {
  if (!lastKnownHeight.has(name) && lastKnownHeight.size >= HEIGHT_CACHE_CAP) {
    const oldest = lastKnownHeight.keys().next().value;
    if (oldest !== undefined) lastKnownHeight.delete(oldest);
  }
  lastKnownHeight.set(name, height);
}

/** Test seam — a module-level cache would otherwise leak between tests. */
export function __resetScreenSlotHeightCache(): void {
  lastKnownHeight.clear();
}

export function AppDNAScreenSlot({
  name,
  minHeight = 0,
  style,
  onContentSizeChange,
}: AppDNAScreenSlotProps): React.ReactElement {
  // Seed from the last height this slot measured, so a remount reserves the right space immediately
  // instead of collapsing to `minHeight` and shifting when the measurement lands.
  const [measuredHeight, setMeasuredHeight] = useState<number | undefined>(() =>
    lastKnownHeight.get(name),
  );

  // A `name` change on a LIVE instance is a different slot: the lazy initializer above only runs on
  // the first mount, so without this the container kept the previous slot's height. If nothing is
  // published for the new name, native never fires `onContentSizeChange` and that stale height stays
  // forever — a block of empty space, which is the very layout defect the height cache exists to
  // prevent.
  const [renderedName, setRenderedName] = useState(name);
  if (renderedName !== name) {
    setRenderedName(name);
    setMeasuredHeight(lastKnownHeight.get(name));
  }

  const handleSize = useCallback(
    (event: NativeSyntheticEvent<{ width: number; height: number }>) => {
      const { width, height } = event.nativeEvent;
      rememberHeight(name, height);
      setMeasuredHeight(height);
      onContentSizeChange?.({ width, height });
    },
    [name, onContentSizeChange],
  );

  return (
    <AppdnaScreenSlotView
      name={name}
      onContentSizeChange={handleSize}
      style={[{ height: measuredHeight ?? minHeight }, style]}
    />
  );
}
