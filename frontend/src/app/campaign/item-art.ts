/**
 * Identifying item icon inside a rarity frame (P17.1-B, docs/23 sections 3 and 5): the stable image of one item
 * definition when the campaign has one, the neutral built-in icon otherwise.
 *
 * The frame is decorative and follows only the validated rarity tier; the tier name and the item name stay live text
 * beside it, so the image carries no information that is not also written. A missing or unreadable image degrades to
 * the built-in icon and never blocks anything.
 */
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { UiIcon } from '../preview/ui-kit';
import { FrameToken } from './campaign-artwork';

/** Show one item's icon. The image is decorative (`alt=""`) because the item's name is always written beside it. */
@Component({
  selector: 'app-item-art', imports: [UiIcon], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="art-frame" [attr.data-frame]="frame()" [attr.data-art]="shown() ? 'image' : 'fallback'" aria-hidden="true">
    @if (shown()) { <img [src]="src()" alt="" width="48" height="48" decoding="async" loading="lazy" (error)="failed.set(src())" /> }
    @else { <app-ui-icon [name]="fallback()" /> }
  </span>`,
})
export class ItemArt {
  /** Hash URL of the item's artwork, or null to show the built-in icon. */
  readonly src = input<string | null>(null);
  /** Built-in icon (a `ui-kit` icon name) shown without artwork. */
  readonly fallback = input('inventory');
  /** Frame treatment of the item's rarity tier. */
  readonly frame = input<FrameToken>('none');
  /** The address that failed to load; a new address is tried again. */
  protected readonly failed = signal<string | null>(null);
  protected readonly shown = computed(() => this.src() !== null && this.failed() !== this.src());
}
