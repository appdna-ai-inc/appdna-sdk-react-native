// The RN preset is what lets jest parse TS + the RN module syntax the facade
// uses. Without it `__tests__/sharedFixtures.test.ts` cannot even be loaded — which is why the
// suite "executed nowhere" before this phase.
module.exports = {
  presets: ['module:@react-native/babel-preset'],
};
