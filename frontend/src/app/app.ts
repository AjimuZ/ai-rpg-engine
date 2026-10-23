/** Neutral application shell: accepts no inputs and declares no adventure state. */
import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Render the startup shell; Angular reports template/bootstrap failures normally. */
@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
