// React adds hidden `$ACTION_*` fields to forms bound to server actions, so they never count
// as user input when a form is checked for unexpected fields.
const serverActionFieldPrefix = "$ACTION_";

export function hasOnlyExpectedFormFields(
  formData: FormData,
  expectedFields: readonly string[],
): boolean {
  const fieldNames = [...formData.keys()].filter(
    (name) => !name.startsWith(serverActionFieldPrefix),
  );

  return (
    fieldNames.length === expectedFields.length &&
    fieldNames.every((name) => expectedFields.includes(name))
  );
}
