/** Character-creation shell validates a name without claiming to implement pack rules or saving. */
import { ChangeDetectionStrategy, Component, inject, linkedSignal } from '@angular/core';
import { form, FormField, validate } from '@angular/forms/signals';
import { PreviewState } from './preview-state';
import { WorkspaceContext } from './workspace';

/** Accept a local name form and expose an explicit unsaved summary; invalid input leaves no summary. */
@Component({
  selector: 'app-creation', imports: [FormField], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="panel narrow"><h1 i18n="@@creation.title">New playthrough</h1><p i18n="@@creation.intro">Character creation preview for {{ context.adventure()?.title }}.</p>
      <p i18n="@@creation.unavailable">Pack choices, budgets and campaign creation will appear when campaign services are connected.</p>
      <form (submit)="preview($event)" novalidate>
        <label for="character-name">{{ context.term('characterName') }}</label>
        <input id="character-name" [formField]="fields.name" aria-describedby="name-help" />
        <p id="name-help" i18n="@@creation.help">Enter a name of 1–60 characters. This preview does not save it.</p>
        @if (attempted() && fields.name().invalid()) { <p role="alert" i18n="@@creation.nameError">Enter a name of 1–60 characters.</p> }
        <button type="submit" i18n="@@creation.preview">Preview character</button>
      </form>
      @if (summary(); as character) {
        @if (character.adventureId === context.adventure()?.id) { <p role="status" i18n="@@creation.receipt">{{ character.name }} is ready to preview. No campaign has been created.</p> }
      }
      <button disabled i18n="@@creation.create">Create campaign</button>
    </section>
  `,
})
export class Creation {
  readonly context = inject(WorkspaceContext);
  private readonly state = inject(PreviewState);
  readonly model = linkedSignal({
    source: this.context.adventure,
    // A reused route must not carry a character name into a different adventure.
    computation: /** Start a fresh local form whenever the scoped identity changes. */ () => ({ name: '' }),
  });
  readonly attempted = linkedSignal({
    source: this.context.adventure,
    computation: /** A fresh scope has no submitted validation errors. */ () => false,
  });
  readonly summary = linkedSignal({
    source: this.context.adventure,
    computation: /** Discard the previous scope's unsaved result, including on return navigation. */ (): { name: string; adventureId: string } | undefined => undefined,
  });
  readonly fields = form(this.model, /** Enforce whitespace-aware required input and a bounded preview name. */ (path) => {
    validate(path.name, /** Match service validation after trimming so padding never changes name validity. */ ({ value }) => {
      const name = value().trim();
      return name.length > 0 && name.length <= 60 ? undefined : { kind: 'name', message: $localize`:@@creation.nameError:Enter a name of 1–60 characters.` };
    });
  });

  /** Prevent browser submission and publish only a valid local summary; invalid values clear it. */
  preview(event: Event): void {
    event.preventDefault();
    this.attempted.set(true);
    this.summary.set(this.state.previewCharacter(this.context.adventure()?.id ?? '', this.model().name));
  }
}
