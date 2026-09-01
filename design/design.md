# Design (manual)

## Content

- Core
  - [Common](Common)
  - [Config](Config)
  - [Urility(Utility)]
- Decker
  - [Decker](Decker)
  - [Programs](Programs)
  - [Accessory](Accessory)
- System
  - [Network](Network)
  - [IC](IC)
  - [Operations](Operations)
- Game
  - [Combat](Combat)
  - [Game](Game)

## Logic

- Logic (non-initialization, non-virtual attribute)
  - [Common](Common)
    - ConditionMonitor: applyDamage
  - [Network](Network)
    - AlertTransition: applyAlertTransition 
  - [Operations](Operations)
    - SystemTestResolver / called by Decker
  - [Combat](Combat)
    - CombatTest / called by IC or Decker
  - [Decker](Decker)
    - ActiveIcon:action --> actions of Decker
  - [IC](IC)
    - ActiveIcon:action --> actions of IC
  - [Game](Game)
    - ActiveIcon (BaseClass)
    - Game (GameLoop)


## Common

- Enums 
- SecurityRating
- SubsystemRating
- ConditionMonitor

- ActiveIcon (--> Game)

## Utility

- DiceRoller

## Decker

- Decker : ActiveIcon

### Data Classes

- Things
  - Cyberdeck
  - Cyberterminal
- Virtual / ResultTypes
  - ActiveMemory
    - PendingUpload
    - LoadUtilityResult
  - DownloadDestination
  - MedicResult

## IC

- IC : ActiveIcon

## Operations

- MatrixIcon
- SystemOperation (Definitions)
- SystemTestResolver

- Virtual
  - BufferedMessage
    - BufferedMessage
    - LinkedObserver
  - DownloadHandle  
  - InterrogationState
  - MatrixIcon
    - SensorTestResult
    - IcDetectionResult
  - MonitoredOperationHandle
  - NullOperationModifier
  - OperationResult
    - OperationResult
    - HostInfoItem
    - ...Result
  - PointerChain
- SystemTestOutcome
  
## Programs (Data Classes)

- PersonaProgram : Program
- Utility[UtilityType] : Program

## Accessory (DataClass)

- Accessory

## Network (Data Classes)

- Matrix
  - RTG : Grid
    - (P)LTG : Grid

- Host
  - Node (`data class Node(val subsystemType: SubsystemType, val description: String = "")`)
  - SAN
  - IC
  - DataFile
  - RemoteDevice

- Virtual Types
  - Jackpoint
  - MatrixLocation
  - TriggerStep
  - SecuritySheaf

- helper Function
  - applyAlertTransition

## Config

### Helper

- DeckCatalogEntry

### Loaded

- DeckCatalogLoader
- DeckerLoader
- GridInitializer / GridLoader
