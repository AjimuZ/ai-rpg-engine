/**
 * Thumbnail of one registered image (P17.1-C, docs/23 section 3): the stored image drawn inside a fixed small box.
 *
 * It is the original bytes scaled by the browser, not a separate stored variant, and it is decorative (`alt=""`)
 * because the subject reference is always written beside it. An unreadable image degrades to a neutral built-in icon.
 */
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { UiIcon } from '../preview/ui-kit';

/** Show one thumbnail; a changed address is tried again after a failure. */
@Component({
  selector: 'app-art-thumb', imports: [UiIcon], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="art-thumb" [attr.data-art]="shown() ? 'image' : 'fallback'" aria-hidden="true">
    @if (shown()) { <img [src]="src()" alt="" width="64" height="64" decoding="async" loading="lazy" (error)="failed.set(src())" /> }
    @else { <app-ui-icon name="inventory" /> }
  </span>`,
})
export class ArtThumb {
  /** Hash URL of the image. */
  readonly src = input.required<string>();
  /** The address that failed to load. */
  protected readonly failed = signal<string | null>(null);
  protected readonly shown = computed(() => this.failed() !== this.src());
}
