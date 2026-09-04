import { useState } from 'react'
import type { MatrixObjectDto } from '../types/messages'

interface Props {
  visibleObjects: MatrixObjectDto[]
}

type EntityKind = 'HostSubsystem' | 'IcProgram' | 'File' | 'Device'
const ENTITY_KINDS: EntityKind[] = ['HostSubsystem', 'IcProgram', 'File', 'Device']

function isEntity(obj: MatrixObjectDto): obj is Extract<MatrixObjectDto, { kind: EntityKind }> {
  return ENTITY_KINDS.includes(obj.kind as EntityKind)
}

function EF({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="entity-field">
      <span className="ef-label">{label}</span>
      <span className="ef-value">{value}</span>
    </div>
  )
}

function EntityCard({
  obj,
  focused,
  onClick,
}: {
  obj: Extract<MatrixObjectDto, { kind: EntityKind }>
  focused: boolean
  onClick?: () => void
}) {
  const cls = `entity-card ${focused ? 'focused' : 'compact'} ${onClick ? 'clickable' : ''}`

  return (
    <div
      className={cls}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
      onKeyDown={onClick ? (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onClick()
        }
      } : undefined}
    >
      <div className="entity-kind">{obj.kind.toUpperCase()}</div>
      {obj.kind === 'IcProgram' && (
        <>
          <div className="entity-name">
            {obj.name}
            {obj.analyzed
              ? <span className="badge badge-green">[ANALYZED]</span>
              : <span className="badge badge-gray">[UNKNOWN]</span>}
          </div>
          {obj.analyzed && <EF label="RATING" value={obj.rating} />}
          {obj.analyzed && <EF label="BEHAVIOR" value={obj.behavior} />}
          {obj.analyzed && obj.guardedNodeType && <EF label="GUARDS" value={obj.guardedNodeType} />}
        </>
      )}
      {obj.kind === 'HostSubsystem' && (
        <>
          <div className="entity-name">{obj.subsystemType}</div>
          <EF label="DESC" value={obj.description} />
        </>
      )}
      {obj.kind === 'File' && (
        <>
          <div className="entity-name">
            {obj.name}
            {obj.isScrambleProtected && <span className="badge badge-red">SCRAMBLED</span>}
            {obj.isPointer && <span className="badge badge-amber">POINTER</span>}
          </div>
          <EF label="SIZE" value={`${obj.sizeMp} MP`} />
        </>
      )}
      {obj.kind === 'Device' && (
        <>
          <div className="entity-name">{obj.name}</div>
          <EF label="ADDR" value={obj.systemAddress} />
        </>
      )}
    </div>
  )
}

export default function EntitiesPanel({ visibleObjects }: Props) {
  const entities = visibleObjects.filter(isEntity)
  // Track focus by the entity's stable DTO index, not its position in the re-derived array,
  // so focus follows the same entity across state broadcasts (F-2). Falls back to the first
  // entity when nothing is focused yet or the focused entity is no longer visible.
  const [focusedIndex, setFocusedIndex] = useState<number | null>(null)
  const focused = entities.find((e) => e.index === focusedIndex) ?? entities[0]

  return (
    <div className="panel entities-panel">
      <div className="panel-header">ENTITIES</div>
      <div className="panel-body">
        {entities.length === 0 ? (
          <div className="no-data">[ NO ENTITIES VISIBLE ]</div>
        ) : (
          <>
            <EntityCard obj={focused} focused />
            {entities
              .filter((e) => e.index !== focused.index)
              .map((obj) => (
                <EntityCard
                  key={obj.index}
                  obj={obj}
                  focused={false}
                  onClick={() => setFocusedIndex(obj.index)}
                />
              ))}
          </>
        )}
      </div>
    </div>
  )
}
