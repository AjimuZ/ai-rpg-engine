/**
 * Type of reviewed XLIFF catalogs that specs import as text with `with { loader: 'text' }`.
 * The application itself never imports a catalog: the build inlines the reviewed targets.
 */
declare module '*.xlf' {
  const text: string;
  export default text;
}
