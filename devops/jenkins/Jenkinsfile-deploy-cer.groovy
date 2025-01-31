@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server'
        NAME_IMG_DOCKER = 'config-server-cer'
        CONTAINER_PORT = '8887'
        HOST_PORT = '8887'
        NETWORK = 'azure-net-cer'
        GIT_CREDENTIALS = credentials('PATH_Jenkins')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
        GIT_BRANCH = 'develop'
    }

    parameters {
        string(name: 'NEW_VERSION', defaultValue: '1.0.0', description: 'Nueva version para el release (ej. 1.0.0)')
        booleanParam(name: 'DOCKER', defaultValue: true, description: 'Desplegar y Ejecutar APP en DOCKER?')
        booleanParam(name: 'NOTIFICATION', defaultValue: false, description: 'Deseas notificar por correo?')
        string(name: 'CORREO', defaultValue: 'josephcarlos.jcmn@gmail.com', description: 'Correo para notificaciones')
    }

    stages {
        stage('Checkout Code') {
            steps {
                echo "######################## : ======> Clonando codigo desde la rama release..."
                script {
                    bat """
                        if exist .git (
                            echo "Repositorio ya clonado, ejecutando git pull..."
                            git reset --hard
                            git pull origin develop
                        ) else (
                            echo "Repositorio no encontrado, clonando..."
                            git clone -b ${GIT_BRANCH} https://${env.GIT_CREDENTIALS_USR}:${env.GIT_CREDENTIALS_PSW}@github.com/josephmn/config-server.git .
                        )
                    """
                }
            }
        }

        stage('Delete Release Branch') {
            steps {
                echo "######################## : ======> Delete Release Branch: release/${params.NEW_VERSION}..."
                script {
                    // Verificar si la rama local existe
                    def localBranchExists = bat(
                            script: """git show-ref refs/heads/release/${params.NEW_VERSION}""",
                            returnStatus: true
                    ) == 0

                    if (localBranchExists) {
                        echo "=========> La rama existe en local, procediendo a eliminarla..."
                        bat "git branch -d release/${params.NEW_VERSION}"
                        echo "=========> Rama local 'release/${params.NEW_VERSION}' eliminada."
                    } else {
                        echo "=========> La rama local 'release/${params.NEW_VERSION}' no existe."
                    }

                    // Verificar si la rama remota existe
                    def branchExists = bat(
                            script: "git show-ref --verify --quiet refs/remotes/origin/release/${params.NEW_VERSION}",
                            returnStatus: true
                    ) == 0

                    if (branchExists) {
                        echo "=========> La rama existe en remoto, procediendo a eliminarla..."
                        bat "git push origin --delete release/${params.NEW_VERSION}"
                        echo "=========> Rama remota release/${params.NEW_VERSION} eliminada"
                    } else {
                        echo "=========> La rama remota 'release/${params.NEW_VERSION}' no existe."
                    }
                }
            }
        }

        stage('Create Release Branch') {
            steps {
                echo "######################## : ======> Create Release Branch..."
                script {
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"

                        echo "=========> Creando rama de release..."
                        git checkout -b release/${params.NEW_VERSION}
                        git push origin release/${params.NEW_VERSION}
                    """

                    bat """
                        mvn versions:set -DnewVersion=${params.NEW_VERSION}
                    """
                    bat """
                        mvn versions:commit
                    """

                    bat """
                        git add .
                        git commit -m "RC version ${params.NEW_VERSION}"
                        git push origin release/${params.NEW_VERSION}
                    """
                }
            }
        }

        stage('Delete Last Tag Release Candidate') {
            steps {
                echo "######################## : ======> Eliminando el ultimo tag que contiene '${params.NEW_VERSION}'..."
                script {
                    def lastTag = bat(
                            script: "@git tag -l \"*${params.NEW_VERSION}*\" --sort=-v:refname",
                            returnStdout: true
                    ).trim()

                    if (lastTag?.trim()) {  // Validamos que lastTag no sea null y no esté vacío
                        echo "=========> Ultimo tag encontrado: ${lastTag}"

                        try {
                            // Borrar el tag localmente
                            bat "git tag -d ${lastTag}"
                            echo "=========> Tag eliminado localmente"

                            // Borrar el tag en el repositorio remoto
                            bat "git push origin --delete ${lastTag}"
                            echo "=========> Tag eliminado del repositorio remoto"
                        } catch (Exception e) {
                            error "Error al eliminar el tag: ${e.getMessage()}"
                        }
                    } else {
                        echo "=========> No se encontro ningun tag con version: RC-${params.NEW_VERSION}-CERT-*"
                    }
                }
            }
        }

        stage('Create Release Candidate') {
            steps {
                echo "######################## : ======> Generando Release Candidate (RC-${params.NEW_VERSION}-CERT-*)..."
                script {
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"

                        echo "=========> Verificando cambios pendientes..."
                        git status
                    """

                    bat """
                        echo "=========> Cambiando a rama release/${params.NEW_VERSION}..."
                        git checkout release/${params.NEW_VERSION}
                    """

                    bat """
                        @echo off
                        REM Obtener la fecha en formato YYYYMMDD
                        for /f "tokens=2 delims==" %%a in ('wmic os get localdatetime /value ^| find "="') do set datetime=%%a
                        set today=%datetime:~0,8%
                        set time=%datetime:~8,4%
                        set tagName=RC-${params.NEW_VERSION}-CERT-%today%%time%

                        echo "=========> tagName: %tagName%"
                        git tag %tagName%
                        git push origin %tagName%
                    """
                }
            }
        }

        stage('Creating Network for Docker') {
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO CREACIÓN DE RED PARA DOCKER..."
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "######################## : ======> La red '${NETWORK}' no existe. Creándola..."
                        bat "docker network create --attachable ${NETWORK}"
                    } else {
                        echo "######################## : ======> La red '${NETWORK}' ya existe. No es necesario crearla."
                    }
                }
            }
        }

        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER }
            }
            steps {
                script {
                    echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."

                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()

                    echo "######################## : ======> VERSIÓN RC: ${version}"
                    echo "######################## : ======> APLICATIVO + VERSION: ${NAME_APP}:${version}"
                    // Usar la versión capturada para los comandos Docker

                    echo "=========> Eliminando contenedores con nombre: ${NAME_APP}..."
                    bat """
                        for /f "tokens=*" %%i in ('docker ps -q --filter "name=${NAME_APP}"') do docker stop %%i
                        for /f "tokens=*" %%i in ('docker ps -a -q --filter "name=${NAME_APP}"') do docker rm %%i
                    """

                    echo "=========> Eliminando imagenes con nombre: ${NAME_APP}..."
                    bat """
                        for /f "tokens=*" %%i in ('docker images -q --filter "reference=${NAME_APP}*"') do docker rmi %%i
                    """

                    bat """
                        echo "=========> Construyendo nueva imagen con versión ${version}..."
                        docker build --build-arg NAME_APP=${NAME_APP} --build-arg JAR_VERSION=${version} -t ${NAME_IMG_DOCKER}:${version} .

                        echo "=========> Desplegando el contenedor: ${NAME_APP}..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} --env SERVER_PORT=${HOST_PORT} ${NAME_IMG_DOCKER}:${version}
                    """
                }
            }
        }

//        stage('Push Docker Image') {
//            steps {
//                script {
//                    def version = readFile('version.txt').trim()
//                    def registry = "docker.io/josephmn"
//
//                    echo "######################## : ======> Subiendo imagen Docker al registry..."
//                    bat """
//                        docker tag ${NAME_APP}:${version} ${registry}/${NAME_APP}:${version}
//                        docker push ${registry}/${NAME_APP}:${version}
//                    """
//                }
//            }
//        }
    }

    post {
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
