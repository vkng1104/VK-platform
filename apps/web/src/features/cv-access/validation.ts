export interface ValidationResult {
  value?: string;
  error?: string;
}

const maxEmailLength = 254;
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const challengeIDPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const verificationCodePattern = /^\d{6}$/;

export function validateEmailAddress(value: unknown): ValidationResult {
  if (typeof value !== "string") {
    return { error: "Enter an email address." };
  }

  const email = value.trim().toLowerCase();
  if (!email) {
    return { error: "Enter an email address." };
  }

  if (email.length > maxEmailLength || !emailPattern.test(email)) {
    return { error: "Enter a valid email address." };
  }

  return { value: email };
}

export function validateChallengeID(value: unknown): ValidationResult {
  if (typeof value !== "string" || !challengeIDPattern.test(value.trim())) {
    return {
      error: "The verification request is invalid. Request a new code and try again.",
    };
  }

  return { value: value.trim() };
}

export function validateVerificationCode(value: unknown): ValidationResult {
  if (typeof value !== "string" || !verificationCodePattern.test(value.trim())) {
    return { error: "Enter the six-digit verification code." };
  }

  return { value: value.trim() };
}

export function hasOnlyExpectedFormFields(
  formData: FormData,
  expectedFields: readonly string[],
): boolean {
  const fieldNames = [...formData.keys()].filter(
    (name) => !name.startsWith("$ACTION_"),
  );

  return (
    fieldNames.length === expectedFields.length &&
    fieldNames.every((name) => expectedFields.includes(name))
  );
}
