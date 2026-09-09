import type { ReactNode } from 'react'
import type { DeckerStateDto, MatrixObjectDto } from '../types/messages'

interface Props {
  gameState: { decker: DeckerStateDto; visibleObjects: MatrixObjectDto[] }
}

function locKey(location: string): { prefix: string; name: string } {
  const prefixes = ['RTG: ', 'LTG: ', 'PLTG: ', 'Host: ']
  for (const p of prefixes) {
    if (location.startsWith(p)) return { prefix: p.slice(0, -2), name: location.slice(p.length) }
  }
  return { prefix: '', name: location }
}

function Field({ label, value, cls }: { label: string; value: ReactNode; cls?: string }) {
  return (
    <div className="loc-field">
      <div className="loc-field-label">{label}</div>
      <div className={`loc-field-value ${cls ?? ''}`}>{value}</div>
    </div>
  )
}

function SecCode({ code }: { code: string | null }) {
  if (code === null) return <span style={{ color: 'var(--green-dim)' }}>???</span>
  return <span className={`sec-${code}`}>{code}</span>
}

function LocationFields({ obj, visibleObjects }: { obj: MatrixObjectDto; visibleObjects: MatrixObjectDto[] }) {
  switch (obj.kind) {
    case 'GridNode':
      return (
        <>
          <Field label="REGION" value={obj.region} />
          <Field label="SEC" value={<SecCode code={obj.securityCode} />} />
          {obj.securityValue !== null && <Field label="SEC VALUE" value={obj.securityValue} />}
          <Field label="ALERT" value={obj.alertStatus.replace('_', ' ')} cls={`alert-${obj.alertStatus}`} />
          <Field label="LTGs" value={obj.ltgCount} />
          <Field label="RTGs" value={obj.connectedRtgCount} />
        </>
      )
    case 'LocalGrid': {
      const parentRtg = visibleObjects.find(
        (o): o is Extract<MatrixObjectDto, { kind: 'GridNode' }> =>
          o.kind === 'GridNode' && o.name === obj.parentRtgName
      ) ?? null
      return (
        <>
          {parentRtg && <Field label="REGION" value={parentRtg.region} />}
          {parentRtg && <Field label="SEC" value={<SecCode code={parentRtg.securityCode} />} />}
          {obj.securityValue !== null && <Field label="SEC VALUE" value={obj.securityValue} />}
          <Field label="ALERT" value={obj.alertStatus.replace('_', ' ')} cls={`alert-${obj.alertStatus}`} />
          <Field label="HOSTS" value={obj.hostCount} />
          <Field label="PLTGs" value={obj.pltgCount} />
        </>
      )
    }
    case 'PrivateGrid':
      return (
        <>
          <Field label="OWNER" value={obj.owner} />
          <Field label="PARENT LTG" value={obj.parentLtgName} />
          <Field label="SEC" value={<SecCode code={obj.securityCode} />} />
          {obj.securityValue !== null && <Field label="SEC VALUE" value={obj.securityValue} />}
          <Field label="ALERT" value={obj.alertStatus.replace('_', ' ')} cls={`alert-${obj.alertStatus}`} />
          <Field label="HOSTS" value={obj.hostCount} />
        </>
      )
    case 'HostNode':
      return (
        <>
          <Field label="TOPOLOGY" value={obj.topologyType.replace('_', ' ')} />
          <Field label="ALERT" value={obj.alertStatus.replace('_', ' ')} cls={`alert-${obj.alertStatus}`} />
          <Field label="SEC CODE" value={<SecCode code={obj.securityCode} />} />
          {obj.securityValue !== null && <Field label="SEC VALUE" value={obj.securityValue} />}
          {obj.offline && <Field label="STATUS" value="OFFLINE" cls="loc-offline" />}
        </>
      )
    default:
      return null
  }
}

export default function LocationPanel({ gameState }: Props) {
  const { decker, visibleObjects } = gameState
  const { prefix, name } = locKey(decker.location)

  const locationObj = !decker.jackedIn
    ? null
    : decker.locationIndex != null
      ? (visibleObjects[decker.locationIndex] as MatrixObjectDto | undefined) ?? null
      : visibleObjects.find(
          (o) =>
            (o.kind === 'GridNode' || o.kind === 'LocalGrid' || o.kind === 'PrivateGrid' || o.kind === 'HostNode') &&
            o.name === name
        ) ?? null

  return (
    <div className="panel location-panel">
      <div className="panel-header">LOCATION</div>
      <div className="panel-body">
        {!decker.jackedIn ? (
          <div className="loc-field">
            <div className="loc-field-value" style={{ color: 'var(--green-dim)', fontSize: 20 }}>
              [ NOT JACKED IN ]
            </div>
          </div>
        ) : (
          <>
            <div className="loc-header">
              {prefix && <span className="loc-prefix">{prefix}:&nbsp;</span>}
              <span className="loc-name">{name}</span>
            </div>
            {locationObj && <LocationFields obj={locationObj} visibleObjects={visibleObjects} />}
          </>
        )}
      </div>
    </div>
  )
}
