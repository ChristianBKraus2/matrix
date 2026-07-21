# Game

## Active Icons

The decker and the IC should have a common base class that exposes the following methods:

- initiative (Rolling for initiative)
- action (acting on the game)

In the case of an IC, the corresponding class implements both methods. The initative roll delegates to the corresponding initiative method and the action delegates to the corresponding action of the IC type in the CombatResolver class.
The decker also implements both methods and delegates to its initialive roll class, but the action method is empty for the time beeing. There will be a callback to the user in the future.

## Game Class

The game class hold all active icons and their corresponding initiative. When an icon is to act it calls the correspondent instance method.

There are two modes:

- outside of combat each decker has one action per turn and is called accordingly. There are no active ICs yet.
- in combat there are turns. At the start of each turn everyone is rolling for initiative. The icon with the highest initiative value starts (and is called). Afterwards, the initiative value is reduced by 10. As long as the initiative value is postive the icon can action. Assuming there are two icons (A and B) with initiatives of 15 and 8 respectively. Then A gets an action with 15, B gets an action with 8 and A gets an action again with 5. There are no further actions as the resulting initiative roll is non-positive. After the end of a turn, a new turn starts (rolling initiative again) until the combat is resolved.

## IC Actions

When an IC takes an action the it uses its appropriate method of CombatResolver. The action is taken against the unauthorized decker in the same node (if there are more then one take an arbitrary one). If there is no unauthorized decker in the same node, look for any unauthorized deckers in the same host and move to the corresponding node (if you mode do not attack).
