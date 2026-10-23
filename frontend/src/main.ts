/** Bootstrap the browser shell; report rejected startup through Angular's error path. */
import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig).catch(
  /** Log the startup error; input is Angular's failure and no recovery is attempted. */
  (error: unknown) => console.error(error),
);
